package dev.dmitriikonovalov.example.usermgmt.support;

import dev.dmitriikonovalov.example.usermgmt.service.PermissionCategories;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *   <li>an in-process {@link OpaClient} that evaluates the same rules {@code team.rego} does (Phase 6.7,
 *       category-driven): allow when the verb is in the role's EFFECTIVE actions for the resource type
 *       (category tokens expanded through the shared table minus {@code denied_actions}), OR when the
 *       verb is one of the two owner-only fence verbs and the role's code is the reserved {@code owner} —
 *       so the genuine role resolution + policy logic run, just without the network hop.</li>
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

    /**
     * Mirrors {@code team.rego} (Phase 6.7, category-driven) for the single-decision path: allow iff the
     * verb is in the role's EFFECTIVE actions for the resource type (the granted category tokens expanded
     * through the shared {@link PermissionCategories#EXPANSION} table — the parity-pinned mirror of the
     * OPA data file — minus {@code denied_actions}), OR the verb is an owner-only fence verb and the
     * role's code is {@code "owner"}. The user-service has no list-filtering endpoints, so the
     * data-filtering methods are conservative fail-closed stubs (deny-all / all-false) — never exercised.
     */
    @Bean
    @Primary
    OpaClient inProcessTeamOpaClient() {
        return new OpaClient() {
            @Override
            public boolean allow(AbacContext context) {
                RoleDefinition roleDefinition = context.roleDefinition();
                if (roleDefinition == null) {
                    return false; // no role definition -> default deny (as in team.rego)
                }
                String verb = verbOf(context.action());
                if (verb == null) {
                    return false;
                }
                // (1) category-driven — verb in effective_actions(role_def, resource.type)
                if (effectiveActions(roleDefinition, context.resource().type()).contains(verb)) {
                    return true;
                }
                // (2) owner-only fence — keyed on the reserved owner CODE, never role_level
                return OWNER_ONLY_FENCE.contains(verb) && "owner".equals(roleDefinition.code());
            }

            @Override
            public PartialResult compile(AbacContext context) {
                return PartialResult.denyAll(); // not used by the user-service; fail closed
            }

            @Override
            public List<Boolean> allowAll(List<AbacContext> contexts) {
                return Collections.nCopies(contexts.size(), Boolean.FALSE); // not used; fail closed
            }
        };
    }

    /** The two verbs authorized only by the owner-only-by-code fence in {@code team.rego}. */
    private static final Set<String> OWNER_ONLY_FENCE = Set.of("define-roles", "transfer-ownership");

    /**
     * The fine actions a role grants for a type — category tokens expanded through the shared table,
     * minus the denied fine actions. Mirrors {@code permissions.effective_actions}: the concrete type
     * key wins, the {@code "*"} wildcard backs it up when the type key is absent (the same lookup for
     * denials), and an unknown/stale token expands to nothing (the fail-closed floor).
     */
    private static Set<String> effectiveActions(RoleDefinition roleDefinition, String type) {
        Set<String> expanded =
                new LinkedHashSet<>(PermissionCategories.expand(tokensFor(roleDefinition.permissions(), type)));
        // denied_actions are already fine actions — subtract them after expansion (deny-overrides).
        expanded.removeAll(tokensFor(roleDefinition.deniedActions(), type));
        return expanded;
    }

    /** Concrete type key wins; the {@code "*"} wildcard applies only when the type key is absent. */
    private static List<String> tokensFor(Map<String, List<String>> map, String type) {
        if (map.containsKey(type)) {
            return map.get(type);
        }
        return map.getOrDefault("*", List.of());
    }

    private static String verbOf(String action) {
        if (action == null) {
            return null;
        }
        String[] parts = action.split(":");
        return parts.length == 2 ? parts[1] : null;
    }
}
