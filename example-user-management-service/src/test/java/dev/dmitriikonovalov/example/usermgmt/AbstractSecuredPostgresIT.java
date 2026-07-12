package dev.dmitriikonovalov.example.usermgmt;

import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for ITs that exercise the <em>real</em> secured chain + the dogfooded {@code @OpaPreAuthorize}
 * path. Imports {@link AbacTestConfig} (a controllable subject extractor + an in-process OPA client
 * mirroring {@code team.rego}) so the genuine role-resolution + policy logic runs against a real
 * Postgres without an OPA container. Tests switch the acting identity with {@code AbacTestConfig.actAs}.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(AbacTestConfig.class)
abstract class AbstractSecuredPostgresIT {

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
        // Turn the starter's OPA wiring on so the real @OpaPreAuthorize advisor is active; the
        // AbacTestConfig OpaClient overrides the HTTP one, so no container is needed.
        registry.add("opa.abac.enabled", () -> "true");
    }
}
