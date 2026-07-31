package dev.dmitriikonovalov.example.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * I22 + I23: the roster adapter is installed into the <strong>real</strong> application context, and the
 * server really is speaking the streamable transport it was pinned to.
 *
 * <p>These are the two things unit tests structurally cannot show. {@link ToolRosterFilterTest} proves
 * every semantic of the filter, and all of it would be dead code if the handler the SDK invokes were the
 * raw one, or if {@code POST /mcp} did not route at all — which was this module's actual state through
 * T1–T4, and which {@code McpServerSecurityTest} could never have caught, because a 401 is returned
 * before routing is ever reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RosterFilterInstallationTest {

    private static final String PROTOCOL_VERSION = "2025-11-25";

    @LocalServerPort
    int port;

    @Autowired
    McpStreamableServerTransportProvider transportProvider;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    // --- I23 : the entry the server invokes IS the wrapping handler -------------------------------

    @Test // I23 — the ToolGateInstallationTest analog for the list path
    @SuppressWarnings("unchecked")
    void theHandlerMapsToolsListEntryIsTheWrappingHandler() throws Exception {
        Object sessionFactory = read(transportProvider, RosterFilterInstaller.SESSION_FACTORY_FIELD);
        Map<String, McpRequestHandler<?>> handlers =
                (Map<String, McpRequestHandler<?>>) read(
                        sessionFactory, RosterFilterInstaller.REQUEST_HANDLERS_FIELD);

        assertThat(handlers.get(McpSchema.METHOD_TOOLS_LIST))
                .as("every roster semantic is dead code unless THIS is what the server invokes")
                .isInstanceOf(RosterFilterInstaller.RosterFilteringHandler.class);
    }

    @Test // the transport really is streamable — the bean the adapter resolves by interface type exists
    void theServerRunsTheStreamableTransport() {
        assertThat(transportProvider)
                .as("with spring.ai.mcp.server.protocol absent the auto-config serves legacy SSE instead")
                .isNotNull();
    }

    // --- I22 : the wire ---------------------------------------------------------------------------

    @Test // I22 — the streamable handshake at POST /mcp, with the session id as a RESPONSE header
    void initializeAnswersJsonAndAssignsASessionIdHeader() throws Exception {
        HttpResponse<String> initialize = post(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"%s","capabilities":{},
                  "clientInfo":{"name":"roster-installation-test","version":"0.0.1"}}}
                """.formatted(PROTOCOL_VERSION));

        assertThat(initialize.statusCode())
                .as("POST /mcp must ROUTE — a 404 here means the protocol pin did not take effect")
                .isEqualTo(200);
        assertThat(initialize.headers().firstValue("content-type").orElse(""))
                .startsWith("application/json");
        assertThat(initialize.headers().firstValue("mcp-session-id")).isPresent();
    }

    @Test // I22 — a follow-up REQUEST on the same session comes back SSE-framed, through the filter
    void aFollowUpRequestIsSseFramedAndPassesThroughTheInstalledFilter() throws Exception {
        String sessionId = handshake();

        HttpResponse<String> list = post(sessionId, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("content-type").orElse(""))
                .as("post-handshake requests are SSE-framed on the same endpoint")
                .contains("text/event-stream");

        JsonNode response = jsonRpcResponse(list);
        assertThat(response.path("result").has("tools"))
                .as("the wrapped handler must still answer a well-formed ListToolsResult")
                .isTrue();
        // No cut is asserted here: neither OPA nor the user-service is running in this context, so the
        // roster's own failure semantics decide the outcome. What I22 pins is that the request routes,
        // frames, and survives the installed wrapper. The cut itself is ToolRosterFilterTest's job.
    }

    @Test // I22 — a NOTIFICATION is 202 with an empty body, not SSE. The e2e collection must not parse it.
    void aNotificationIsAcceptedWithNoBody() throws Exception {
        String sessionId = handshake();

        HttpResponse<String> notification =
                post(sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        assertThat(notification.statusCode()).isEqualTo(202);
        assertThat(notification.body()).isEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------------

    private String handshake() throws Exception {
        HttpResponse<String> initialize = post(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"%s","capabilities":{},
                  "clientInfo":{"name":"roster-installation-test","version":"0.0.1"}}}
                """.formatted(PROTOCOL_VERSION));
        String sessionId = initialize.headers().firstValue("mcp-session-id").orElseThrow();
        post(sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        return sessionId;
    }

    private HttpResponse<String> post(String sessionId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + forwardedJwt())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("mcp-session-id", sessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A gateway-forwarded token. The app runs with {@code trust-forwarded-jwt: true} — an explicit
     * acknowledgment that a signature-validating gateway fronts it — so the starter decodes the payload
     * without verifying the signature, exactly as it does on the rig.
     */
    private static String forwardedJwt() {
        // `exp` is mandatory: the starter still validates expiry without the signature (cheap
        // defense-in-depth), and fails closed on a token that carries no verifiable one.
        long expiresAt = java.time.Instant.now().plusSeconds(3600).getEpochSecond();
        return b64url("{\"alg\":\"RS256\"}") + "."
                + b64url("{\"sub\":\"user-alice\",\"preferred_username\":\"alice\",\"exp\":"
                        + expiresAt + "}")
                + ".not-verified-behind-the-gateway";
    }

    private static String b64url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Object read(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Parse either framing — plain JSON, or the SSE {@code data:} lines the transport emits. */
    private JsonNode jsonRpcResponse(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.contains("text/event-stream")) {
            return json.readTree(response.body());
        }
        List<JsonNode> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : response.body().split("\n", -1)) {
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).strip());
            } else if (line.isBlank() && !data.isEmpty()) {
                events.add(json.readTree(data.toString()));
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) {
            events.add(json.readTree(data.toString()));
        }
        return events.stream()
                .filter(node -> node.has("result") || node.has("error"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JSON-RPC response:\n" + response.body()));
    }
}
