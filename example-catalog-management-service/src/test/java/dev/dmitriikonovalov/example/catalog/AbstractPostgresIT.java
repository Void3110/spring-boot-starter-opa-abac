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
 */
@SpringBootTest
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
