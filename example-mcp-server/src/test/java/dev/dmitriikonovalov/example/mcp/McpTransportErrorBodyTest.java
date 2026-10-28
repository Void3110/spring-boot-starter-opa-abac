package dev.dmitriikonovalov.example.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

/**
 * U10 — the streamable transport's own refusals name their error and nothing else.
 *
 * <p>Spring AI 2.0.1's transport answers these by handing the whole {@code McpError} to the HTTP message
 * converters, which serialize the exception's every bean property — a 117-frame {@code stackTrace},
 * {@code cause}, {@code suppressed}, about 29 KB — to any caller with a token (QA E13 found it through Claude
 * Code's first post-login request). The example's Jackson mix-in keeps the JSON-RPC error and the message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTransportErrorBodyTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-11-25","capabilities":{},
              "clientInfo":{"name":"transport-error-body-test","version":"0.0.1"}}}
            """;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test // U10 — 400: an Accept header without text/event-stream (Claude Code 2.1.286's first request)
    void anInvalidAcceptHeaderAnswersTheErrorWithoutAStackTrace() throws Exception {
        HttpResponse<String> response = post(INITIALIZE, "application/json", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertOnlyTheError(response.body(), "Invalid Accept headers");
    }

    @Test // U10 — 404: a session id the server does not know
    void anUnknownSessionAnswersTheErrorWithoutAStackTrace() throws Exception {
        HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "application/json, text/event-stream", "no-such-session");

        assertThat(response.statusCode()).isEqualTo(404);
        assertOnlyTheError(response.body(), "Session not found");
    }

    @SuppressWarnings("unchecked")
    private static void assertOnlyTheError(String body, String expectedMessage) {
        Map<String, Object> json = JsonMapper.builder().build().readValue(body, Map.class);

        assertThat(json).containsOnlyKeys("jsonRpcError", "message");
        assertThat((Map<String, Object>) json.get("jsonRpcError")).containsKeys("code", "message");
        assertThat((String) json.get("message")).contains(expectedMessage);
        assertThat(body).doesNotContain("stackTrace").doesNotContain("dev.dmitriikonovalov");
    }

    private HttpResponse<String> post(String body, String accept, String sessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("Authorization", "Bearer " + forwardedJwt())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("mcp-session-id", sessionId).header("MCP-Protocol-Version", "2025-11-25");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A gateway-forwarded token: the app trusts the gateway's signature check and still validates {@code exp}. */
    private static String forwardedJwt() {
        long expiresAt = java.time.Instant.now().plusSeconds(3600).getEpochSecond();
        return b64url("{\"alg\":\"RS256\"}") + "."
                + b64url("{\"sub\":\"user-alice\",\"preferred_username\":\"alice\",\"exp\":" + expiresAt + "}")
                + ".not-verified-behind-the-gateway";
    }

    private static String b64url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
