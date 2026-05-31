package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpOpaClient} against an in-process {@link HttpServer} stub — no WireMock.
 * Covers QA cases U1–U8 (allow/deny round-trip, every fail-closed path, request shape, resolved path).
 */
class HttpOpaClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AbacContext sampleContext() {
        AbacContext.Subject subject =
                new AbacContext.Subject("user-1", List.of("catalog-viewer"), Map.of("username", "alice"));
        AbacContext.Resource resource = new AbacContext.Resource("product", "p-1", Map.of());
        RoleDefinition roleDefinition = new RoleDefinition(
                "catalog-viewer", Map.of("role_level", 10), Map.of("product", List.of("read")));
        return new AbacContext(subject, "product:read", resource, roleDefinition, Map.of());
    }

    /** Start a stub OPA server with the given handler; return the base URL. */
    private String startServer(StubHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpOpaClient clientFor(String baseUrl, String policyPrefix) {
        OpaClientConfig config = new OpaClientConfig(baseUrl, Duration.ofMillis(500), "allow");
        return new HttpOpaClient(MAPPER, new PerTypePolicyPathResolver(policyPrefix), config);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Test // U1
    void allow_whenOpaReturnsTrue() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":{\"allow\":true}}"));
        assertThat(clientFor(base, "catalog").allow(sampleContext())).isTrue();
    }

    @Test // U2
    void deny_whenOpaReturnsFalse() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":{\"allow\":false}}"));
        assertThat(clientFor(base, "catalog").allow(sampleContext())).isFalse();
    }

    @Test // U3
    void failClosed_onHttp500() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        assertThat(clientFor(base, "catalog").allow(sampleContext())).isFalse();
    }

    @Test // U4
    void failClosed_onConnectionRefused() {
        // Nothing listening on this port → connection refused, no server started.
        HttpOpaClient client = clientFor("http://127.0.0.1:1", "catalog");
        assertThat(client.allow(sampleContext())).isFalse();
    }

    @Test // U5
    void failClosed_onTimeout() throws IOException {
        String base = startServer(ex -> {
            try {
                Thread.sleep(2000); // longer than the 500ms request timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });
        assertThat(clientFor(base, "catalog").allow(sampleContext())).isFalse();
    }

    @Test // U6a
    void failClosed_onMalformedBody() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not-json"));
        assertThat(clientFor(base, "catalog").allow(sampleContext())).isFalse();
    }

    @Test // U6b
    void failClosed_onMissingDecisionField() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":{\"other\":true}}"));
        assertThat(clientFor(base, "catalog").allow(sampleContext())).isFalse();
    }

    @Test // U6c
    void failClosed_onNonBooleanDecision() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":{\"allow\":\"yes\"}}"));
        assertThat(clientFor(base, "catalog").allow(sampleContext())).isFalse();
    }

    @Test // U6d
    void failClosed_onEmptyResult() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{}"));
        assertThat(clientFor(base, "catalog").allow(sampleContext())).isFalse();
    }

    @Test // U7 — request body shape, incl. role_definition
    void requestBody_hasInputWrapperWithRoleDefinition() throws IOException {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        String base = startServer(ex -> {
            captured.set(ex.getRequestBody().readAllBytes());
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        clientFor(base, "catalog").allow(sampleContext());

        JsonNode root = MAPPER.readTree(captured.get());
        JsonNode input = root.get("input");
        assertThat(input).isNotNull();
        assertThat(input.get("subject").get("id").asText()).isEqualTo("user-1");
        assertThat(input.get("action").asText()).isEqualTo("product:read");
        assertThat(input.get("resource").get("type").asText()).isEqualTo("product");
        // serialized as snake_case "role_definition"
        JsonNode roleDef = input.get("role_definition");
        assertThat(roleDef).isNotNull();
        assertThat(roleDef.get("code").asText()).isEqualTo("catalog-viewer");
        assertThat(roleDef.get("permissions").get("product").get(0).asText()).isEqualTo("read");
        assertThat(input.get("environment")).isNotNull();
    }

    @Test // U7b — role_definition omitted when null
    void requestBody_omitsRoleDefinitionWhenNull() throws IOException {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        String base = startServer(ex -> {
            captured.set(ex.getRequestBody().readAllBytes());
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        AbacContext noRoleDef = new AbacContext(
                new AbacContext.Subject("user-1", List.of(), Map.of()),
                "product:read",
                new AbacContext.Resource("product", null, Map.of()),
                Map.of());
        clientFor(base, "catalog").allow(noRoleDef);

        JsonNode input = MAPPER.readTree(captured.get()).get("input");
        assertThat(input.has("role_definition")).isFalse();
    }

    @Test // U8 — resolved per-type path
    void resolvedPath_isPerType() throws IOException {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        String base = startServer(ex -> {
            capturedPath.set(ex.getRequestURI().getPath());
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        clientFor(base, "catalog").allow(sampleContext()); // resource type = product

        assertThat(capturedPath.get()).isEqualTo("/v1/data/catalog/product");
    }

    @Test // U8b — blank prefix → just the type
    void resolvedPath_blankPrefix() throws IOException {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        String base = startServer(ex -> {
            capturedPath.set(ex.getRequestURI().getPath());
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        clientFor(base, "").allow(sampleContext());

        assertThat(capturedPath.get()).isEqualTo("/v1/data/product");
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
