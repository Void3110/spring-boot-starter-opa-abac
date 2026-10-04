package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // ENGINE-ERRORS (ADR 0037): when no decision could be obtained the client THROWS — never a fabricated
    // deny. Every cell asserts both entry points, allow and decide, which share one evaluation path.

    @Test // ENGINE-ERRORS U3
    void indeterminate_onNon200_carriesTheStatus() throws IOException {
        for (int status : new int[] {500, 503, 400}) {
            String base = startServer(ex -> respond(ex, status, "boom"));
            HttpOpaClient client = clientFor(base, "catalog");

            assertThat(assertIndeterminate(client, PolicyEngineException.Kind.HTTP_STATUS).httpStatus())
                    .hasValue(status);
            server.stop(0);
        }
    }

    @Test // ENGINE-ERRORS U1
    void indeterminate_onConnectionRefused_isTransport() {
        // Nothing listening on this port → connection refused, no server started.
        HttpOpaClient client = clientFor("http://127.0.0.1:1", "catalog");

        PolicyEngineException e = assertIndeterminate(client, PolicyEngineException.Kind.TRANSPORT);
        assertThat(e).hasCauseInstanceOf(IOException.class);
        assertThat(e.httpStatus()).isEmpty();
    }

    @Test // ENGINE-ERRORS U2
    void indeterminate_onTimeout() throws IOException {
        String base = startServer(ex -> {
            try {
                Thread.sleep(2000); // longer than the 500ms request timeout
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        assertIndeterminate(clientFor(base, "catalog"), PolicyEngineException.Kind.TIMEOUT);
    }

    @Test // ENGINE-ERRORS U4 — interrupt-correct: the flag survives the throw
    void indeterminate_onInterrupt_restoresTheFlag() throws IOException {
        String base = startServer(ex -> {
            try {
                Thread.sleep(300); // the call is still in flight when the caller notices the interrupt
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });
        HttpOpaClient client = clientFor(base, "catalog");

        for (java.util.function.Consumer<AbacContext> call :
                List.<java.util.function.Consumer<AbacContext>>of(client::allow, client::decide)) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> call.accept(sampleContext()))
                        .isInstanceOfSatisfying(PolicyEngineException.class,
                                e -> assertThat(e.kind()).isEqualTo(PolicyEngineException.Kind.INTERRUPTED));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted(); // never leave the test thread interrupted
            }
        }
    }

    @Test // ENGINE-ERRORS U5
    void indeterminate_onMalformedBody() throws IOException {
        for (String body : List.of(
                "not-json",                          // unparseable
                "{\"result\":{\"allow\":\"yes\"}}", // a non-boolean decision
                "{\"result\":{\"allow\":null}}",    // a null decision is not a boolean either
                "{\"result\":null}",                 // an explicit null result is not "no result"
                "{\"result\":true}",                 // a result that is not a document
                "[]")) {                             // a body that is not an object
            String base = startServer(ex -> respond(ex, 200, body));

            assertIndeterminate(clientFor(base, "catalog"), PolicyEngineException.Kind.MALFORMED_RESPONSE);
            server.stop(0);
        }
    }

    @Test // ENGINE-ERRORS U6 — no result at all: the policy is not loaded at this path
    void indeterminate_onNoResult_isUndefinedDecision() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{}"));

        assertIndeterminate(clientFor(base, "catalog"), PolicyEngineException.Kind.UNDEFINED_DECISION);
    }

    @Test // ENGINE-ERRORS U6 — the package is loaded, `allow` is undefined for this input: a real deny
    void deny_onLoadedPackageWithoutTheDecisionField() throws IOException {
        for (String body : List.of("{\"result\":{\"other\":true}}", "{\"result\":{}}")) {
            String base = startServer(ex -> respond(ex, 200, body));
            HttpOpaClient client = clientFor(base, "catalog");

            assertThat(client.allow(sampleContext())).isFalse();
            assertThat(client.decide(sampleContext())).isEqualTo(OpaDecision.deny());
            server.stop(0);
        }
    }

    @Test // ENGINE-ERRORS U8 — a path resolver failure is a refusal (deny), never an engine error
    void deny_whenThePathResolverThrows_andNothingIsSent() throws IOException {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        String base = startServer(ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });
        OpaClientConfig config = new OpaClientConfig(base, Duration.ofMillis(500), "allow");
        HttpOpaClient client = new HttpOpaClient(MAPPER, context -> {
            throw new IllegalStateException("resolver bug");
        }, config);

        assertThat(client.allow(sampleContext())).isFalse();
        assertThat(client.decide(sampleContext())).isEqualTo(OpaDecision.deny());
        assertThat(hits.get()).isZero();
    }

    @Test // ENGINE-ERRORS U8 — the opt-in: a resolver that throws a family member is not a refusal
    void indeterminate_whenThePathResolverOptsIn_propagates() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":{\"allow\":true}}"));
        OpaClientConfig config = new OpaClientConfig(base, Duration.ofMillis(500), "allow");
        HttpOpaClient client = new HttpOpaClient(MAPPER, context -> {
            throw new ResolverOutage();
        }, config);

        assertThatThrownBy(() -> client.allow(sampleContext())).isInstanceOf(ResolverOutage.class);
        assertThatThrownBy(() -> client.decide(sampleContext())).isInstanceOf(ResolverOutage.class);
    }

    /** An adopter's own outage signal, opted into the family by subclassing (ADR 0037 §2). */
    private static final class ResolverOutage extends DecisionIndeterminateException {
        ResolverOutage() {
            super("the policy-path source is down");
        }
    }

    /** Both single-decision entry points throw the same kind; returns the {@code decide} one. */
    private PolicyEngineException assertIndeterminate(HttpOpaClient client, PolicyEngineException.Kind kind) {
        assertThatThrownBy(() -> client.allow(sampleContext()))
                .isInstanceOfSatisfying(PolicyEngineException.class, e -> assertThat(e.kind()).isEqualTo(kind));
        Throwable thrown = catchThrowable(() -> client.decide(sampleContext()));
        assertThat(thrown).isInstanceOf(PolicyEngineException.class);
        PolicyEngineException e = (PolicyEngineException) thrown;
        assertThat(e.kind()).isEqualTo(kind);
        return e;
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
        assertThat(input.get("subject").get("id").asString()).isEqualTo("user-1");
        assertThat(input.get("action").asString()).isEqualTo("product:read");
        assertThat(input.get("resource").get("type").asString()).isEqualTo("product");
        // serialized as snake_case "role_definition"
        JsonNode roleDef = input.get("role_definition");
        assertThat(roleDef).isNotNull();
        assertThat(roleDef.get("code").asString()).isEqualTo("catalog-viewer");
        assertThat(roleDef.get("permissions").get("product").get(0).asString()).isEqualTo("read");
        assertThat(input.get("environment")).isNotNull();
    }

    @Test // W1 (SB4 port) — the EXACT property sets of the OPA input: no field appears or vanishes
    // on Jackson 3 (a serialization default-flip would show up here as an extra/missing property).
    // Also pins absent-vs-null for the NON_EMPTY ancestors: an empty chain stays ABSENT, so a rego
    // `input.resource.ancestors` stays undefined — a defined-but-empty value would flip policy
    // semantics (F4's fail-closed edge).
    void requestBody_exactShape_noFieldAppearsOrVanishes() throws IOException {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        String base = startServer(ex -> {
            captured.set(ex.getRequestBody().readAllBytes());
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        clientFor(base, "catalog").allow(sampleContext());

        JsonNode input = MAPPER.readTree(captured.get()).get("input");
        assertThat(input.propertyNames()).containsExactlyInAnyOrder(
                "subject", "action", "resource", "role_definition", "environment");
        assertThat(input.get("subject").propertyNames())
                .containsExactlyInAnyOrder("id", "roles", "attributes");
        assertThat(input.get("resource").propertyNames())
                .containsExactlyInAnyOrder("type", "id", "attributes"); // ancestors: absent when empty
        assertThat(input.get("role_definition").propertyNames())
                .containsExactlyInAnyOrder("code", "attributes", "permissions");
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

    @Test // U9 — unsafe path segment (traversal) denies without an HTTP call
    void failClosed_onTraversalResourceType() throws IOException {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        String base = startServer(ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        assertThat(clientFor(base, "catalog").allow(contextWithType("product/../secret"))).isFalse();
        assertThat(hits.get()).isZero();
    }

    @Test // U9b — a dot in the type could splice into the compile query → denied everywhere
    void failClosed_onDottedResourceType() throws IOException {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        String base = startServer(ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        assertThat(clientFor(base, "catalog").allow(contextWithType("product.admin"))).isFalse();
        assertThat(hits.get()).isZero();
    }

    @Test // U9c — empty resolved path (no prefix, no type) would address the whole data document
    void failClosed_onEmptyResolvedPath() throws IOException {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        String base = startServer(ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        assertThat(clientFor(base, "").allow(contextWithType(""))).isFalse();
        assertThat(hits.get()).isZero();
    }

    @Test // U9d — a pathological, very long resolved path must fail closed, never overflow the stack.
    // The former (…/…)* regex recursed per segment and threw a StackOverflowError at a few thousand
    // segments; that Error escaped the catch(Exception) fail-closed handler and propagated uncaught.
    // The linear scan denies cleanly with no HTTP call.
    void failClosed_onPathologicallyLongResolvedPath() throws IOException {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        String base = startServer(ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "{\"result\":{\"allow\":true}}");
        });

        String longType = "a" + "/a".repeat(20_000); // ~40k chars, thousands of segments
        assertThat(clientFor(base, "catalog").allow(contextWithType(longType))).isFalse();
        assertThat(hits.get()).isZero();
    }

    private AbacContext contextWithType(String type) {
        AbacContext.Subject subject =
                new AbacContext.Subject("user-1", List.of("catalog-viewer"), Map.of("username", "alice"));
        AbacContext.Resource resource = new AbacContext.Resource(type, "p-1", Map.of());
        return new AbacContext(subject, "product:read", resource, null, Map.of());
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
