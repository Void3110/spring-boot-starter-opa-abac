package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpOpaClient#allowAll(List)} (the batch primitive) against an in-process
 * {@link HttpServer} stub. Covers QA cases U10–U12: positional mapping of a mixed bulk body; every
 * fail-closed path (500 / refused / timeout / malformed / wrong-length → all-false of length N); empty
 * input → empty list with no HTTP call.
 */
class HttpOpaClientAllowAllTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AbacContext ctx(String resourceId) {
        return ctxOfType("category", resourceId);
    }

    private AbacContext ctxOfType(String resourceType, String resourceId) {
        AbacContext.Subject subject = new AbacContext.Subject("user-1", List.of("catalog-viewer"), Map.of());
        AbacContext.Resource resource =
                new AbacContext.Resource(resourceType, resourceId, Map.of("region", "emea"));
        RoleDefinition roleDefinition =
                new RoleDefinition("catalog-viewer", Map.of(), Map.of("category", List.of("read")));
        return new AbacContext(subject, "category:read", resource, roleDefinition, Map.of());
    }

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

    @Test // U10 — mixed bulk body maps positionally; request shape + path pinned
    void mixedBulkBody_mapsPositionally() throws IOException {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        String base = startServer(ex -> {
            capturedPath.set(ex.getRequestURI().getPath());
            captured.set(ex.getRequestBody().readAllBytes());
            respond(ex, 200, "{\"result\":[true,false,true]}");
        });

        List<Boolean> result = clientFor(base, "catalog").allowAll(List.of(ctx("a"), ctx("b"), ctx("c")));

        assertThat(result).containsExactly(true, false, true);
        // POST /v1/data/catalog/category/bulk with {"input":{"items":[<ctx>,…]}}
        assertThat(capturedPath.get()).isEqualTo("/v1/data/catalog/category/bulk");
        JsonNode input = MAPPER.readTree(captured.get()).get("input");
        assertThat(input.get("items")).hasSize(3);
        assertThat(input.get("items").get(0).get("resource").get("id").asText()).isEqualTo("a");
        assertThat(input.get("items").get(1).get("resource").get("id").asText()).isEqualTo("b");
    }

    @Test // U11 — fail-closed on HTTP 500 → all-false of length N
    void failClosed_onHttp500() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        assertThat(clientFor(base, "catalog").allowAll(List.of(ctx("a"), ctx("b"))))
                .containsExactly(false, false);
    }

    @Test // U11 — fail-closed on connection refused
    void failClosed_onConnectionRefused() {
        assertThat(clientFor("http://127.0.0.1:1", "catalog").allowAll(List.of(ctx("a"), ctx("b"), ctx("c"))))
                .containsExactly(false, false, false);
    }

    @Test // U11 — fail-closed on timeout
    void failClosed_onTimeout() throws IOException {
        String base = startServer(ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"result\":[true,true]}");
        });
        assertThat(clientFor(base, "catalog").allowAll(List.of(ctx("a"), ctx("b"))))
                .containsExactly(false, false);
    }

    @Test // U11 — fail-closed on malformed body
    void failClosed_onMalformedBody() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not-json"));
        assertThat(clientFor(base, "catalog").allowAll(List.of(ctx("a"), ctx("b"))))
                .containsExactly(false, false);
    }

    @Test // U11 — fail-closed on a length mismatch (result shorter than N)
    void failClosed_onLengthMismatch() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":[true]}"));
        assertThat(clientFor(base, "catalog").allowAll(List.of(ctx("a"), ctx("b"), ctx("c"))))
                .containsExactly(false, false, false);
    }

    @Test // U11 — fail-closed on a non-boolean element
    void failClosed_onNonBooleanElement() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":[true,\"yes\"]}"));
        assertThat(clientFor(base, "catalog").allowAll(List.of(ctx("a"), ctx("b"))))
                .containsExactly(false, false);
    }

    @Test // U12 — empty input → empty list, no HTTP call made
    void emptyInput_returnsEmpty_noHttpCall() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        String base = startServer(ex -> {
            calls.incrementAndGet();
            respond(ex, 200, "{\"result\":[]}");
        });

        assertThat(clientFor(base, "catalog").allowAll(List.of())).isEmpty();
        assertThat(calls.get()).isZero(); // the stub server was never contacted
    }

    @Test // U12 — null input is also empty, no call
    void nullInput_returnsEmpty() {
        assertThat(clientFor("http://127.0.0.1:1", "catalog").allowAll(null)).isEmpty();
    }

    @Test // a MIXED-resource-type batch is rejected fail-closed (all-false), with no HTTP call —
    // evaluating items against the first item's policy document would be silently wrong
    void failClosed_onMixedResourceTypes_noHttpCall() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        String base = startServer(ex -> {
            calls.incrementAndGet();
            respond(ex, 200, "{\"result\":[true,true]}");
        });

        assertThat(clientFor(base, "catalog").allowAll(List.of(ctx("a"), ctxOfType("product", "b"))))
                .containsExactly(false, false);
        assertThat(calls.get()).isZero();
    }

    @Test // U12c — unsafe resource type in the batch denies all without an HTTP call
    void failClosed_onTraversalResourceType() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String base = startServer(ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "{\"result\":[true,true]}");
        });

        List<Boolean> decisions = clientFor(base, "catalog")
                .allowAll(List.of(ctxOfType("category/../admin", "c-1"), ctxOfType("category/../admin", "c-2")));

        assertThat(decisions).containsExactly(false, false);
        assertThat(hits.get()).isZero();
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
