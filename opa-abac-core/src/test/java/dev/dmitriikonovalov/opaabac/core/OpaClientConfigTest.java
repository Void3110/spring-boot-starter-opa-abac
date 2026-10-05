package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ENGINE-ERRORS U37 — the OPA base URL is validated when the config is built, so a misconfiguration fails the
 * application at startup instead of answering every request as a retried {@code TRANSPORT} fault.
 */
class OpaClientConfigTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    void acceptsAnAbsoluteHttpOrHttpsUrl_andDropsTrailingSlashes() {
        assertThat(new OpaClientConfig("http://localhost:8181/", TIMEOUT).baseUrl())
                .isEqualTo("http://localhost:8181");
        assertThat(new OpaClientConfig("HTTPS://opa.example//", TIMEOUT).baseUrl())
                .isEqualTo("HTTPS://opa.example");
        assertThat(new OpaClientConfig("http://127.0.0.1:8181/opa", TIMEOUT).baseUrl())
                .isEqualTo("http://127.0.0.1:8181/opa");
    }

    @Test
    void rejectsAnythingElse_atConstruction() {
        for (String bad : List.of("opa:8181", "localhost:8181", "/v1", "", "ftp://opa:8181", "http://", "http:///v1",
                "http://op a:8181")) {
            assertThatThrownBy(() -> new OpaClientConfig(bad, TIMEOUT))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("OPA base URL");
        }
    }
}
