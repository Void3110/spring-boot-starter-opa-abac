package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The unguarded-baseline boot contract (ADR 0021 §2): with the starter <strong>off</strong>
 * ({@code opa.abac.enabled=false} — the rig's {@code ENABLE_OPA=0} flip), the app must
 *
 * <ol>
 *   <li><b>boot a real servlet container</b> — regression for the empty
 *       {@code FilterRegistrationBean} that failed Tomcat startup ("Filter must not be null":
 *       {@code RegistrationBean.onStartup} asserts a filter even for a disabled registration), which
 *       is why {@code RANDOM_PORT}, not {@code MOCK}, is essential here;</li>
 *   <li><b>serve the API on gateway trust</b> — no {@code AbacFilter} means nothing can authenticate
 *       the forwarded Bearer, so {@code /api/v1/**} is served openly (the gateway upstream still
 *       validates tokens); and</li>
 *   <li><b>keep the audit posture</b> — actuator beyond health stays denied (retro-audit 2026-06-12),
 *       explicitly, not just unauthenticatably.</li>
 * </ol>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "opa.abac.enabled=false")
@Testcontainers
class UnguardedBootIT {

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

    @Autowired
    TestRestTemplate rest;

    @Test
    void servesTheApiOnGatewayTrust() {
        ResponseEntity<String> list = rest.getForEntity("/api/v1/catalogs", String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthStaysOpen() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuatorBeyondHealthStaysDenied() {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode().value())
                .isIn(401, 403);
    }
}
