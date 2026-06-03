package dev.dmitriikonovalov.example.usermgmt.support;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

/**
 * Test wiring that exercises the <em>real</em> {@code @OpaPreAuthorize} → {@code TeamRoleDefinitionSupplier}
 * → OPA chain without a running OPA container:
 *
 * <ul>
 *   <li>a header-driven {@link AbacSubjectExtractor} — a request authenticates as the subject named in
 *       the {@value #SUBJECT_HEADER} header. A header (not a {@code ThreadLocal}) is used precisely
 *       because the HTTP call runs on a Tomcat worker thread, not the test thread, so the acting
 *       identity must travel <em>with the request</em>;</li>
 *   <li>an in-process {@link OpaClient} that evaluates the same rule {@code team.rego} does — allow
 *       when the action verb is in {@code role_definition.permissions[resource.type]} — so the genuine
 *       role resolution + policy logic run, just without the network hop.</li>
 * </ul>
 */
@TestConfiguration
public class AbacTestConfig {

    /** Tests set this header to the Keycloak {@code sub} the request should authenticate as. */
    public static final String SUBJECT_HEADER = "X-Test-Subject";

    /** An {@link HttpEntity} carrying {@code body} and authenticating as {@code subject}. */
    public static <T> HttpEntity<T> as(String subject, T body) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.add(SUBJECT_HEADER, subject);
        }
        return new HttpEntity<>(body, headers);
    }

    /** A body-less {@link HttpEntity} authenticating as {@code subject} (for GET/DELETE). */
    public static HttpEntity<Void> as(String subject) {
        return as(subject, null);
    }

    @Bean
    @Primary
    AbacSubjectExtractor testSubjectExtractor() {
        return request -> {
            String subject = request.getHeader(SUBJECT_HEADER);
            if (subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AbacContext.Subject(subject, List.of(), Map.of()));
        };
    }

    /** Mirrors team.rego: allow iff the action verb is granted for the resource type by the role def. */
    @Bean
    @Primary
    OpaClient inProcessTeamOpaClient() {
        return context -> {
            var roleDefinition = context.roleDefinition();
            if (roleDefinition == null) {
                return false; // no role definition -> default deny (as in team.rego)
            }
            String verb = verbOf(context.action());
            if (verb == null) {
                return false;
            }
            List<String> granted = roleDefinition.permissions()
                    .getOrDefault(context.resource().type(), List.of());
            return granted.contains(verb);
        };
    }

    private static String verbOf(String action) {
        if (action == null) {
            return null;
        }
        String[] parts = action.split(":");
        return parts.length == 2 ? parts[1] : null;
    }
}
