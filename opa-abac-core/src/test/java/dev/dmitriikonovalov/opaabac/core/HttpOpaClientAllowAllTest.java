package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
 * failure path (500 / refused / timeout / malformed / wrong-length / no result → a thrown
 * {@link PolicyEngineException} of the matching kind, ENGINE-ERRORS); the refusals (mixed types, unsafe
 * path → all-false, no HTTP call); empty input → empty list with no HTTP call.
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
        assertThat(input.get("items").get(0).get("resource").get("id").asString()).isEqualTo("a");
        assertThat(input.get("items").get(1).get("resource").get("id").asString()).isEqualTo("b");
    }

    // ENGINE-ERRORS (ADR 0037): a failed batch THROWS — never a padded all-false list that a caller would
    // read as "every item denied". A refusal (mixed types, unsafe path) stays all-false, below.

    @Test // ENGINE-ERRORS U10 — non-200 → HTTP_STATUS, the status carried
    void indeterminate_onHttp503() throws IOException {
        String base = startServer(ex -> respond(ex, 503, "boom"));

        assertThat(assertKind(clientFor(base, "catalog"), PolicyEngineException.Kind.HTTP_STATUS).httpStatus())
                .hasValue(503);
    }

    @Test // ENGINE-ERRORS U10 — connection refused → TRANSPORT
    void indeterminate_onConnectionRefused() {
        assertKind(clientFor("http://127.0.0.1:1", "catalog"), PolicyEngineException.Kind.TRANSPORT);
    }

    @Test // ENGINE-ERRORS U10 — timeout → TIMEOUT
    void indeterminate_onTimeout() throws IOException {
        String base = startServer(ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"result\":[true,true]}");
        });

        assertKind(clientFor(base, "catalog"), PolicyEngineException.Kind.TIMEOUT);
    }

    @Test // ENGINE-ERRORS U10 — no result: no such package, or a package without a `bulk` rule
    void indeterminate_onNoResult_isUndefinedDecision() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{}"));

        assertKind(clientFor(base, "catalog"), PolicyEngineException.Kind.UNDEFINED_DECISION);
    }

    @Test // ENGINE-ERRORS U10 — every body that is not a boolean list of length N → MALFORMED_RESPONSE
    void indeterminate_onMalformedBody() throws IOException {
        for (String body : List.of(
                "not-json",                       // unparseable
                "{\"result\":null}",              // an explicit null is not "no result"
                "{\"result\":[true]}",            // shorter than the input
                "{\"result\":[true,true,true]}",  // longer than the input
                "{\"result\":[true,\"yes\"]}",    // a non-boolean element
                "{\"result\":{\"a\":true}}")) {   // not a list at all
            String base = startServer(ex -> respond(ex, 200, body));

            assertKind(clientFor(base, "catalog"), PolicyEngineException.Kind.MALFORMED_RESPONSE);
            server.stop(0);
        }
    }

    /** A two-item batch throws the given kind; returns it for further assertions. */
    private PolicyEngineException assertKind(HttpOpaClient client, PolicyEngineException.Kind kind) {
        Throwable thrown = catchThrowable(() -> client.allowAll(List.of(ctx("a"), ctx("b"))));
        assertThat(thrown).isInstanceOf(PolicyEngineException.class);
        PolicyEngineException e = (PolicyEngineException) thrown;
        assertThat(e.kind()).isEqualTo(kind);
        return e;
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
