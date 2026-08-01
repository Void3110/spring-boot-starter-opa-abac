package dev.dmitriikonovalov.example.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import tools.jackson.databind.ObjectMapper;

/**
 * I1–I3: the tools' outbound edge against an in-process {@link HttpServer} standing in for the catalog
 * REST API — no WireMock, mirroring {@code TagDefinitionClientTest}.
 *
 * <p>The load-bearing assertion is I1's negative half: the request carries the caller's bearer
 * <em>verbatim</em> and <strong>no</strong> role, capability, chain, or acting-as header. The catalog
 * service is never asked to trust something this server asserts, which is what lets the two layers compose
 * without propagation.
 */
class CatalogApiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CALLER_TOKEN = "eyJhbGciOiJSUzI1NiJ9.caller-token.signature";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
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

    private CatalogApiClient clientFor(String baseUrl) {
        return clientFor(baseUrl, Duration.ofSeconds(5));
    }

    private CatalogApiClient clientFor(String baseUrl, Duration readTimeout) {
        return new CatalogApiClient(
                MAPPER,
                new FixedBearerSupplier(CALLER_TOKEN),
                new CatalogApiErrorTranslator(MAPPER),
                baseUrl,
                Duration.ofMillis(500),
                readTimeout);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Test // I1
    void forwardsTheCallerBearerVerbatimAndNothingElse() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<List<String>> headerNames = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();

        String base = startServer(ex -> {
            requests.incrementAndGet();
            authorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            headerNames.set(List.copyOf(ex.getRequestHeaders().keySet()));
            path.set(ex.getRequestURI().getPath());
            respond(ex, 200, "{\"items\":[{\"id\":\"c-1\"}],\"totalElements\":1}");
        });

        Map<String, Object> result = clientFor(base).getJson("/api/v1/catalogs");

        assertThat(requests).hasValue(1);
        assertThat(path.get()).isEqualTo("/api/v1/catalogs");
        assertThat(authorization.get()).isEqualTo("Bearer " + CALLER_TOKEN);
        assertThat(result).containsKey("items");

        // Nothing that asserts authority may be added — the exact shape slice B4 removed.
        assertThat(headerNames.get())
                .noneMatch(name -> name.toLowerCase().contains("role"))
                .noneMatch(name -> name.toLowerCase().contains("capabilit"))
                .noneMatch(name -> name.toLowerCase().contains("act"))
                .noneMatch(name -> name.toLowerCase().contains("chain"))
                .noneMatch(name -> name.toLowerCase().contains("subject"))
                .noneMatch(name -> name.toLowerCase().contains("principal"));
    }

    @Test // I1 — a tool argument cannot escape its path segment
    void percentEncodesPathSegments() throws IOException {
        AtomicReference<String> rawPath = new AtomicReference<>();
        String base = startServer(ex -> {
            rawPath.set(ex.getRequestURI().getRawPath());
            respond(ex, 200, "{}");
        });

        clientFor(base).getJson("/api/v1/catalogs/" + CatalogApiClient.segment("c 1/../../admin"));

        assertThat(rawPath.get()).isEqualTo("/api/v1/catalogs/c%201%2F..%2F..%2Fadmin");
    }

    @Test // I2 — the target-gate half of the layer vocabulary
    void translatesADownstream403ToATargetGateAdvisoryError() throws IOException {
        String base = startServer(ex -> respond(ex, 403,
                "{\"type\":\"/problems/access-denied\",\"title\":\"Access denied\",\"status\":403,"
                        + "\"detail\":\"principal u-9 may not view catalog c-1\","
                        + "\"errorCode\":\"ACCESS_DENIED\"}"));

        ToolInvocationException error = catchThrowableOfType(
                ToolInvocationException.class, () -> clientFor(base).getJson("/api/v1/catalogs/c-1"));

        assertThat(error.layer()).isEqualTo(ToolErrorLayer.TARGET_GATE);
        assertThat(error.layerLabel()).isEqualTo("target-gate");
        assertThat(error.code()).isEqualTo("ACCESS_DENIED");
        // The upstream detail names a principal and a resource — it stays in the log, not in the advisory.
        assertThat(error.getMessage()).doesNotContain("u-9").doesNotContain("c-1");
    }

    @Test // I2 — a 403 without a problem+json body still labels the layer
    void labelsTheTargetGateEvenWithoutAnUpstreamErrorCode() throws IOException {
        String base = startServer(ex -> respond(ex, 403, "Forbidden"));

        ToolInvocationException error = catchThrowableOfType(
                ToolInvocationException.class, () -> clientFor(base).getJson("/api/v1/catalogs/c-1"));

        assertThat(error.layer()).isEqualTo(ToolErrorLayer.TARGET_GATE);
        assertThat(error.code()).isEqualTo(CatalogApiErrorTranslator.FORBIDDEN_CODE);
    }

    @Test // I3 — an outage is not an authorization decision, so it carries no layer
    void translatesA500ToAStructuredErrorWithNoLayer() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));

        ToolInvocationException error = catchThrowableOfType(
                ToolInvocationException.class, () -> clientFor(base).getJson("/api/v1/catalogs"));

        assertThat(error.layer()).isNull();
        assertThat(error.code()).isEqualTo(CatalogApiErrorTranslator.UNAVAILABLE_CODE);
    }

    @Test // I3
    void translatesAConnectionRefusalWithoutLeakingTheTransportException() {
        CatalogApiClient client = clientFor("http://127.0.0.1:1");

        ToolInvocationException error = catchThrowableOfType(
                ToolInvocationException.class, () -> client.getJson("/api/v1/catalogs"));

        assertThat(error.code()).isEqualTo(CatalogApiErrorTranslator.UNREACHABLE_CODE);
        assertThat(error.getMessage()).doesNotContain("127.0.0.1").doesNotContain("Connection");
        assertThat(error.getCause()).isNull();
    }

    @Test // I3 — the call is bounded by the configured read timeout rather than hanging
    void translatesAReadTimeoutWithinTheConfiguredBound() throws IOException {
        String base = startServer(ex -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{}");
        });
        CatalogApiClient client = clientFor(base, Duration.ofMillis(200));

        long startedAt = System.nanoTime();
        ToolInvocationException error = catchThrowableOfType(
                ToolInvocationException.class, () -> client.getJson("/api/v1/catalogs"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(error.code()).isEqualTo(CatalogApiErrorTranslator.UNREACHABLE_CODE);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    }

    @Test // I3
    void translatesAMalformedBody() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not json {{{"));

        assertThatThrownBy(() -> clientFor(base).getJson("/api/v1/catalogs"))
                .isInstanceOf(ToolInvocationException.class)
                .extracting(e -> ((ToolInvocationException) e).code())
                .isEqualTo(CatalogApiErrorTranslator.MALFORMED_CODE);
    }

    @Test // I3 — a 200 with no body is not "an empty result", it is unreadable
    void translatesAnEmptyBody() throws IOException {
        String base = startServer(ex -> exchangeWithNoContent(ex));

        assertThatThrownBy(() -> clientFor(base).getJson("/api/v1/catalogs"))
                .isInstanceOf(ToolInvocationException.class)
                .extracting(e -> ((ToolInvocationException) e).code())
                .isEqualTo(CatalogApiErrorTranslator.MALFORMED_CODE);
    }

    private static void exchangeWithNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, -1);
    }

    /** Stands in for the request-scoped supplier; the client's contract is "forward what you are given". */
    private static final class FixedBearerSupplier extends CallerBearerSupplier {

        private final String token;

        private FixedBearerSupplier(String token) {
            this.token = token;
        }

        @Override
        public String currentBearer() {
            return token;
        }
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
