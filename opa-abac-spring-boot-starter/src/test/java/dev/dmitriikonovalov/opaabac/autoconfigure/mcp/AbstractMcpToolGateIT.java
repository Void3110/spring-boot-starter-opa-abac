package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The wire and the recording OPA stub shared by the tool-gate's integration tests (MCP-AUTH-LIBRARY T5).
 * The per-type contract they prove is {@link PerTypeToolGateContract}'s; I6 uses the same wire to prove the
 * customizer trap.
 *
 * <p>The per-type proof obligation of ADR 0036 §4 (I2–I5): over a <strong>real</strong>
 * {@code POST /mcp} — never MockMvc, which cannot expose the transport's async re-dispatch — the caller's
 * identity is visible <em>where the gate decides</em>, a policy deny never reaches the tool body, and a
 * policy allow does.
 *
 * <p>OPA is an in-process {@link HttpServer} stub that records every input it is asked about and answers
 * allow or deny on a switch. "The identity is visible where the gate decides" is asserted on that recorded
 * input: the subject id the gate sent is the forwarded bearer's {@code sub}. With no identity on the
 * deciding thread the gate would never ask the policy at all — it denies {@code tool-gate-unauthenticated}
 * first — so an empty recording is the failure this pins.
 *
 * <p>Each subclass is one server configuration ({@code spring.ai.mcp.server.type} ×
 * {@code spring.ai.mcp.server.protocol}) with the tool shape that configuration scans.
 */
abstract class AbstractMcpToolGateIT {

    static final String PROTOCOL_VERSION = "2025-11-25";
    static final String CALLER = "user-alice";

    static final HttpServer OPA = startOpa();
    static final AtomicBoolean OPA_ALLOWS = new AtomicBoolean();
    static final List<String> OPA_INPUTS = new CopyOnWriteArrayList<>();

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    private static HttpServer startOpa() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                OPA_INPUTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = ("{\"result\":{\"allow\":" + OPA_ALLOWS.get() + "}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void opaProperties(DynamicPropertyRegistry registry) {
        registry.add("opa.abac.base-url", () -> "http://127.0.0.1:" + OPA.getAddress().getPort());
    }

    @AfterAll
    static void keepOpaForTheNextClass() {
        // One stub for the JVM: Spring caches contexts across test classes, and a cached context keeps the
        // base URL it was started with. Stopping it here would strand the next class on a dead port.
        OPA_INPUTS.clear();
    }

    @BeforeEach
    void reset() {
        OPA_INPUTS.clear();
        McpGateTestApplication.BODY_RUNS.set(0);
    }

    /** Whether this configuration keeps a session (streamable) or not (stateless). */
    abstract boolean stateful();

    // --- the MCP wire -----------------------------------------------------------------------------

    /** {@code initialize} → ({@code notifications/initialized}) → {@code tools/call}; the call's result. */
    JsonNode callTool() throws Exception {
        HttpResponse<String> initialize = post(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"%s","capabilities":{},
                  "clientInfo":{"name":"tool-gate-it","version":"0.0.1"}}}
                """.formatted(PROTOCOL_VERSION));
        assertThat(initialize.statusCode()).as("initialize: " + initialize.body()).isEqualTo(200);

        String sessionId = null;
        if (stateful()) {
            sessionId = initialize.headers().firstValue("mcp-session-id").orElseThrow();
            HttpResponse<String> initialized =
                    post(sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            assertThat(initialized.statusCode()).isEqualTo(202);
        }

        HttpResponse<String> call = post(sessionId, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                """.formatted(McpGateTestApplication.TOOL));
        assertThat(call.statusCode()).as("tools/call: " + call.body()).isEqualTo(200);
        JsonNode response = jsonRpcResponse(call);
        assertThat(response.has("result")).as("a JSON-RPC result, not an error: " + response).isTrue();
        return response.path("result");
    }

    private HttpResponse<String> post(String sessionId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + forwardedJwt())
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("mcp-session-id", sessionId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A gateway-forwarded token: the server runs with {@code trust-forwarded-jwt=true}, so the starter decodes
     * the payload without verifying the signature (it still requires an unexpired {@code exp}).
     */
    private static String forwardedJwt() {
        long expiresAt = Instant.now().plusSeconds(3600).getEpochSecond();
        return b64url("{\"alg\":\"RS256\"}") + "."
                + b64url("{\"sub\":\"" + CALLER + "\",\"exp\":" + expiresAt + "}")
                + ".not-verified-behind-the-gateway";
    }

    private static String b64url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Parse either framing — plain JSON, or the SSE {@code data:} lines the streamable transport emits. */
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
