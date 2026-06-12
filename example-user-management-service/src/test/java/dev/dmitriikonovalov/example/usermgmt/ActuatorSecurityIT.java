package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Pins the security chain's actuator posture (retro-audit 2026-06-12, the critic's user-mgmt
 * sibling): only {@code /actuator/health} is anonymous; the broad demo actuator surface
 * ({@code env}, {@code beans}, …) requires an authenticated subject — the old {@code permitAll}
 * catch-all exposed it unauthenticated. The base's extractor is header-driven, so a request without
 * {@code X-Test-Subject} is genuinely anonymous.
 */
class ActuatorSecurityIT extends AbstractSecuredPostgresIT {

    @Autowired
    private TestRestTemplate rest;

    @Test // health stays open — the container/gateway probe needs no token
    void health_isAnonymous() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test // env (and the rest of the broad demo surface) is NOT readable anonymously
    void env_requiresAuthentication() {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity("/actuator/beans", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test // an authenticated subject (any) may read the demo actuator surface
    void env_readableWithASubject() {
        ResponseEntity<String> response = rest.exchange(
                "/actuator/env", HttpMethod.GET, AbacTestConfig.as("it-operator"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
