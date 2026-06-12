package dev.dmitriikonovalov.example.catalog;

import dev.dmitriikonovalov.example.catalog.support.PermissiveSecurityTestConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for integration tests that need a real Postgres. Spins up a single
 * {@code postgres:16-alpine} container (shared across subclasses via the static field +
 * Testcontainers reuse) and points Spring's datasource at it. Liquibase runs the real
 * Postgres-dialect changelog, so these tests exercise the actual schema we deploy.
 *
 * <p>Imports {@link PermissiveSecurityTestConfig} so these persistence/concurrency tests pass through
 * the real (now secured) chain without a token — authorization is covered elsewhere.
 *
 * <p>Resource resolution (Phase 5.97) is <strong>off</strong> here: these suites test CRUD and
 * persistence, not gate semantics, and with resolution on a missing id answers {@code 403} at the
 * gate instead of the handler's {@code 404} they pin. Running them on the kill-switch off-state
 * doubles as the byte-identical baseline proof; the gate semantics live in
 * {@code ResourceResolutionGateIT}.
 */
@SpringBootTest(properties = "opa.abac.resource-resolution.enabled=false")
@Testcontainers
@Import(PermissiveSecurityTestConfig.class)
abstract class AbstractPostgresIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
