package dev.dmitriikonovalov.example.usermgmt;

import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for integration tests that need a real Postgres. Spins up a single
 * {@code postgres:16-alpine} container (shared across subclasses via the static field) and points
 * Spring's datasource at it. Liquibase runs the real Postgres-dialect changelog, so these tests
 * exercise the actual schema we deploy — and {@code ddl-auto: validate} proves the JPA mappings
 * match it.
 *
 * <p>Imports {@link AbacTestConfig} so the <em>real</em> {@code SecurityConfig} chain is exercised
 * (one chain everywhere — no second test chain). Subclasses that hit authenticated endpoints attach
 * the {@link AbacTestConfig#SUBJECT_HEADER} header to authenticate; the dogfooded authorization
 * decisions are covered by the secured ITs (see {@link AbstractSecuredPostgresIT}).
 */
@SpringBootTest
@Testcontainers
@Import(AbacTestConfig.class)
abstract class AbstractPostgresIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usermgmt")
            .withUsername("usermgmt")
            .withPassword("usermgmt");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Wire the starter on so the real security beans (AbacFilter, the @OpaPreAuthorize advisor)
        // are present; AbacTestConfig's @Primary OpaClient + extractor override the HTTP ones.
        registry.add("opa.abac.enabled", () -> "true");
    }
}
