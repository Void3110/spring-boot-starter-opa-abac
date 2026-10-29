package dev.dmitriikonovalov.example.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * U4–U6 — the document as served, through the server's real security chain (ADR 0035 §6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        ProtectedResourceDocument.RESOURCE_KEY + "=http://localhost:9085/mcp",
        ProtectedResourceDocument.AUTHORIZATION_SERVER_KEY + "=http://localhost:9085/realms/catalog-demo"})
class ProtectedResourceMetadataEndpointTest {

    private static final String DOCUMENT = "{\"resource\":\"http://localhost:9085/mcp\","
            + "\"authorization_servers\":[\"http://localhost:9085/realms/catalog-demo\"],"
            + "\"bearer_methods_supported\":[\"header\"]}";

    @LocalServerPort
    int port;

    @ParameterizedTest(name = "U4 — GET {0}") // U4
    @ValueSource(strings = {ProtectedResourceMetadataController.ROOT_PATH, ProtectedResourceMetadataController.MCP_PATH})
    void servesExactlyTheConfiguredDocumentWithoutAToken(String path) throws Exception {
        RawHttp response = RawHttp.exchange(port, "GET", path);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.body()).isEqualTo(DOCUMENT);
    }

    @ParameterizedTest(name = "U5 — GET {0} with a forged Host and forwarding headers") // U5
    @ValueSource(strings = {ProtectedResourceMetadataController.ROOT_PATH, ProtectedResourceMetadataController.MCP_PATH})
    void neverDerivesTheDocumentFromTheRequest(String path) throws Exception {
        RawHttp response = RawHttp.exchange(port, "GET", path,
                "Host: attacker.invalid", "X-Forwarded-Host: attacker.invalid", "X-Forwarded-Proto: https",
                "Forwarded: host=attacker.invalid;proto=https");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(DOCUMENT);
    }

    @ParameterizedTest(name = "U6 — unauthenticated GET {0} is refused") // U6
    @ValueSource(strings = {
            ProtectedResourceMetadataController.ROOT_PATH + "/other",
            ProtectedResourceMetadataController.MCP_PATH + "/",
            "/.well-known/openid-configuration",
            "/.well-known/oauth-authorization-server"})
    void permitsNoOtherWellKnownPath(String path) throws Exception {
        assertThat(RawHttp.exchange(port, "GET", path).status()).isEqualTo(403);
    }

    @ParameterizedTest(name = "U6 — unauthenticated POST {0} is refused") // U6
    @ValueSource(strings = {ProtectedResourceMetadataController.ROOT_PATH, ProtectedResourceMetadataController.MCP_PATH})
    void permitsOnlyGetOnTheDocumentPaths(String path) throws Exception {
        assertThat(RawHttp.exchange(port, "POST", path).status()).isEqualTo(403);
    }

    @Test // U6 — the tool surface itself stays authenticated
    void theMcpEndpointStillRefusesAnAnonymousCaller() throws Exception {
        assertThat(RawHttp.exchange(port, "POST", "/mcp").status()).isIn(401, 403);
    }
}
