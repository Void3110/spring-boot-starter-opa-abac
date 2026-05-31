package dev.dmitriikonovalov.example.catalog.support;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Permissive security for the persistence/concurrency ITs. They send no token and test the schema +
 * controllers + concurrency, not authorization. The real (now secured) chain and method security stay
 * wired — so the boot path is genuinely exercised — but every request passes:
 *
 * <ul>
 *   <li>an {@link AbacSubjectExtractor} that always yields a fixed editor subject, so the library's
 *       real {@code AbacFilter} (inside the security chain) authenticates every request — surviving
 *       Spring Security's {@code SecurityContextHolderFilter}, which a plain servlet filter would not;</li>
 *   <li>a stub {@link OpaClient} that always allows, so {@code @OpaPreAuthorize} grants.</li>
 * </ul>
 *
 * Both are {@code @ConditionalOnMissingBean} in the starter, so these beans override the real ones.
 * Authorization itself is covered by the security-module unit tests and the e2e allow/deny matrix.
 */
@TestConfiguration
public class PermissiveSecurityTestConfig {

    /** A stable test principal (a real UUID, so the auditor records it). */
    public static final UUID TEST_PRINCIPAL = UUID.fromString("00000000-0000-0000-0000-0000000a11ce");

    @Bean
    AbacSubjectExtractor permissiveSubjectExtractor() {
        AbacContext.Subject editor = new AbacContext.Subject(
                TEST_PRINCIPAL.toString(), List.of("catalog-editor"), Map.of("username", "it-editor"));
        return request -> Optional.of(editor);
    }

    @Bean
    OpaClient allowAllOpaClient() {
        return context -> true;
    }
}
