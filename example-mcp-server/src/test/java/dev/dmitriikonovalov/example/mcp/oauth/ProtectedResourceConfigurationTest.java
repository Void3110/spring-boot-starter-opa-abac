package dev.dmitriikonovalov.example.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * U1–U3 — the document's startup contract (ADR 0035 §6): off is a legitimate state, half-configured and
 * malformed are startup failures naming the key and the reason.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProtectedResourceConfigurationTest {

    private static final String RESOURCE = "http://localhost:9085/mcp";
    private static final String ISSUER = "http://localhost:9085/realms/catalog-demo";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ProtectedResourceConfiguration.class);

    @Test // U1
    void bothUnsetIsDiscoveryOffWithOneWarnNamingBothKeys(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProtectedResourceDocument.class).isEnabled()).isFalse();
        });

        String warn = "Protected-resource discovery is OFF: " + ProtectedResourceDocument.RESOURCE_KEY + " and "
                + ProtectedResourceDocument.AUTHORIZATION_SERVER_KEY + " are unset";
        assertThat(output.getOut().split(java.util.regex.Pattern.quote(warn), -1)).hasSize(2);
    }

    @Test // U1 — blank counts as unset
    void blankValuesAreUnset() {
        runner.withPropertyValues(ProtectedResourceDocument.RESOURCE_KEY + "=  ",
                        ProtectedResourceDocument.AUTHORIZATION_SERVER_KEY + "=")
                .run(context -> assertThat(context.getBean(ProtectedResourceDocument.class).isEnabled()).isFalse());
    }

    @Test // U2
    void resourceWithoutAnAuthorizationServerFailsStartup() {
        runner.withPropertyValues(ProtectedResourceDocument.RESOURCE_KEY + "=" + RESOURCE)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith(ProtectedResourceDocument.AUTHORIZATION_SERVER_KEY + " is not set"));
    }

    @Test // U2
    void anAuthorizationServerWithoutAResourceFailsStartup() {
        runner.withPropertyValues(ProtectedResourceDocument.AUTHORIZATION_SERVER_KEY + "=" + ISSUER)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith(ProtectedResourceDocument.RESOURCE_KEY + " is not set"));
    }

    @ParameterizedTest(name = "U3 — {0}=''{1}'' fails: {2}") // U3
    @CsvSource(delimiter = '|', value = {
            "resource             | /mcp                                      | is not an absolute http(s) URL",
            "resource             | ftp://localhost:9085/mcp                  | is not an absolute http(s) URL",
            "resource             | http://localhost:9085/mcp#top             | carries a fragment",
            "resource             | http://localhost:9085/mcp/                | must name the MCP endpoint",
            "resource             | http://localhost:9085/                    | must name the MCP endpoint",
            "resource             | http://localhost:9085                     | must name the MCP endpoint",
            "resource             | http://localhost:9085/api/mcp             | must name the MCP endpoint",
            "authorization-server | realms/catalog-demo                       | is not an absolute http(s) URL",
            "authorization-server | mailto:keycloak@example.invalid           | is not an absolute http(s) URL",
            "authorization-server | http://localhost:9085/realms/catalog-demo#x | carries a fragment",
    })
    void aMalformedValueFailsStartupNamingTheKeyAndTheReason(String key, String value, String reason) {
        String resource = key.equals("resource") ? value : RESOURCE;
        String issuer = key.equals("authorization-server") ? value : ISSUER;
        String fullKey = "example.mcp.oauth.protected-resource." + key;
        runner.withPropertyValues(ProtectedResourceDocument.RESOURCE_KEY + "=" + resource,
                        ProtectedResourceDocument.AUTHORIZATION_SERVER_KEY + "=" + issuer)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith(fullKey + "='" + value + "'")
                        .hasMessageContaining(reason));
    }

    @Test // both valid: on, nothing logged
    void bothValidIsOn(CapturedOutput output) {
        runner.withPropertyValues(ProtectedResourceDocument.RESOURCE_KEY + "=" + RESOURCE,
                        ProtectedResourceDocument.AUTHORIZATION_SERVER_KEY + "=" + ISSUER)
                .run(context -> assertThat(context.getBean(ProtectedResourceDocument.class).isEnabled()).isTrue());
        assertThat(output.getOut()).doesNotContain("Protected-resource discovery is OFF");
    }
}
