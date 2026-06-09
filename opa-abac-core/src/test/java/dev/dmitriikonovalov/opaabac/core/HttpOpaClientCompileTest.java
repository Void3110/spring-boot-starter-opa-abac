package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Unit tests for {@link HttpOpaClient#compile(AbacContext)} against an in-process {@link HttpServer} stub
 * (no WireMock). Canned {@code /v1/compile} bodies drive every decision and fail-closed path. Covers QA
 * cases U1–U9 (residual model + request shape).
 *
 * <p>The canned bodies are the real OPA Compile API shapes, verified against OPA 1.x: an unsatisfiable
 * query returns {@code {"result": {}}} (→ {@code DENY_ALL}); an unconditional query returns
 * {@code {"result": {"queries": [[]]}}} (→ {@code ALLOW_ALL}); a conditional query returns
 * {@code {"result": {"queries": [[expr,…]]}}}.
 */
class HttpOpaClientCompileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AbacContext categoryListContext() {
        AbacContext.Subject subject =
                new AbacContext.Subject("user-1", List.of("catalog-viewer"), Map.of("username", "alice"));
        // The resource carries only the TYPE for path resolution; its attributes are the unknown.
        AbacContext.Resource resource = new AbacContext.Resource("category", null, Map.of());
        RoleDefinition roleDefinition = new RoleDefinition(
                "catalog-viewer", Map.of("role_level", 10), Map.of("category", List.of("read")));
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

    // The residual expression {input.resource.attributes.region == "emea"} as the real OPA AST.
    private static final String COND_REGION_EQ_EMEA =
            "{\"index\":1,\"terms\":["
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"eq\"}]},"
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                    + "{\"type\":\"string\",\"value\":\"resource\"},"
                    + "{\"type\":\"string\",\"value\":\"attributes\"},"
                    + "{\"type\":\"string\",\"value\":\"region\"}]},"
                    + "{\"type\":\"string\",\"value\":\"emea\"}]}";

    // The resource-type binding {input.resource.type == "category"} — a tautology the parser drops.
    private static final String TYPE_BINDING =
            "{\"index\":0,\"terms\":["
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"eq\"}]},"
                    + "{\"type\":\"string\",\"value\":\"category\"},"
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                    + "{\"type\":\"string\",\"value\":\"resource\"},"
                    + "{\"type\":\"string\",\"value\":\"type\"}]}]}";

    @Test // U1 — trivially-true (empty conjunction) → ALLOW_ALL
    void allowAll_whenQueryIsUnconditional() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":{\"queries\":[[]]}}"));
        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.ALLOW_ALL);
    }

    @Test // U2 — empty result (unsatisfiable) → DENY_ALL (the fail-closed boundary)
    void denyAll_whenResultIsEmpty() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":{}}"));
        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
    }

    @Test // U3 — single-condition residual → CONDITIONAL with one Condition (type binding dropped)
    void conditional_singleCondition() throws IOException {
        String body = "{\"result\":{\"queries\":[[" + TYPE_BINDING + "," + COND_REGION_EQ_EMEA + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.CONDITIONAL);
        assertThat(result.clauses()).hasSize(1);
        List<Condition> conds = result.clauses().get(0).conditions();
        assertThat(conds).hasSize(1); // the tautological type binding was dropped
        Condition c = conds.get(0);
        assertThat(c.path()).isEqualTo("tags.region");
        assertThat(c.operator()).isEqualTo(Condition.Operator.EQ);
        assertThat(c.value()).isEqualTo("emea");
    }

    @Test // U4 — DNF residual (region==emea OR sensitivity in {public}) → two Conjunctions; EQ + IN parsed
    void conditional_dnf_withInOperator() throws IOException {
        // second disjunct: internal.member_2(input.resource.attributes.sensitivity, {"public"})
        String inExpr =
                "{\"index\":0,\"terms\":["
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"internal\"},"
                        + "{\"type\":\"string\",\"value\":\"member_2\"}]},"
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                        + "{\"type\":\"string\",\"value\":\"resource\"},"
                        + "{\"type\":\"string\",\"value\":\"attributes\"},"
                        + "{\"type\":\"string\",\"value\":\"sensitivity\"}]},"
                        + "{\"type\":\"set\",\"value\":[{\"type\":\"string\",\"value\":\"public\"}]}]}";
        String body = "{\"result\":{\"queries\":["
                + "[" + COND_REGION_EQ_EMEA + "],"
                + "[" + inExpr + "]"
                + "]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.CONDITIONAL);
        assertThat(result.clauses()).hasSize(2);
        Condition eq = result.clauses().get(0).conditions().get(0);
        assertThat(eq.operator()).isEqualTo(Condition.Operator.EQ);
        assertThat(eq.path()).isEqualTo("tags.region");
        Condition in = result.clauses().get(1).conditions().get(0);
        assertThat(in.operator()).isEqualTo(Condition.Operator.IN);
        assertThat(in.path()).isEqualTo("tags.sensitivity");
        assertThat(in.value()).isEqualTo(List.of("public"));
    }

    @Test // U5 — CONTAINS: member_2(literal, resourceRef) ("v in resource.tags.region") → Operator.CONTAINS
    void conditional_containsFromMembership() throws IOException {
        // internal.member_2("emea", input.resource.attributes.region) — literal LEFT, resource ref RIGHT.
        String containsExpr =
                "{\"index\":0,\"terms\":["
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"internal\"},"
                        + "{\"type\":\"string\",\"value\":\"member_2\"}]},"
                        + "{\"type\":\"string\",\"value\":\"emea\"},"
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                        + "{\"type\":\"string\",\"value\":\"resource\"},"
                        + "{\"type\":\"string\",\"value\":\"attributes\"},"
                        + "{\"type\":\"string\",\"value\":\"region\"}]}]}";
        String body = "{\"result\":{\"queries\":[[" + containsExpr + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.CONDITIONAL);
        Condition c = result.clauses().get(0).conditions().get(0);
        assertThat(c.operator()).isEqualTo(Condition.Operator.CONTAINS);
        assertThat(c.path()).isEqualTo("tags.region");
        assertThat(c.value()).isEqualTo("emea");
    }

    @Test // U6 — intrinsic-column residual (input.resource.id == <uuid>) → non-tags path
    void conditional_intrinsicColumn() throws IOException {
        String idExpr =
                "{\"index\":0,\"terms\":["
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"eq\"}]},"
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                        + "{\"type\":\"string\",\"value\":\"resource\"},"
                        + "{\"type\":\"string\",\"value\":\"id\"}]},"
                        + "{\"type\":\"string\",\"value\":\"cat-123\"}]}";
        String body = "{\"result\":{\"queries\":[[" + idExpr + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.CONDITIONAL);
        Condition c = result.clauses().get(0).conditions().get(0);
        assertThat(c.path()).isEqualTo("id"); // intrinsic, not tags.*
        assertThat(c.isTagPath()).isFalse();
        assertThat(c.value()).isEqualTo("cat-123");
    }

    @Test // U7 — fail-closed on HTTP 500, flagged fromError (a failed call, not a policy answer)
    void failClosed_onHttp500() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fromError()).isTrue();
    }

    @Test // U7 — fail-closed on connection refused, flagged fromError
    void failClosed_onConnectionRefused() {
        PartialResult result = clientFor("http://127.0.0.1:1", "catalog").compile(categoryListContext());
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fromError()).isTrue();
    }

    @Test // U7 — fail-closed on timeout, flagged fromError
    void failClosed_onTimeout() throws IOException {
        String base = startServer(ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"result\":{\"queries\":[[]]}}");
        });
        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fromError()).isTrue();
    }

    @Test // U7 — fail-closed on malformed body, flagged fromError
    void failClosed_onMalformedBody() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not-json"));
        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fromError()).isTrue();
    }

    @Test // U8 — unsupported operator (e.g. gt) anywhere in the residual → DENY_ALL
    void failClosed_onUnsupportedOperator() throws IOException {
        String gtExpr =
                "{\"index\":0,\"terms\":["
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"gt\"}]},"
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                        + "{\"type\":\"string\",\"value\":\"resource\"},"
                        + "{\"type\":\"string\",\"value\":\"attributes\"},"
                        + "{\"type\":\"string\",\"value\":\"score\"}]},"
                        + "{\"type\":\"number\",\"value\":5}]}";
        String body = "{\"result\":{\"queries\":[[" + gtExpr + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());
        // Fail-closed (deny) BUT flagged not-fully-SQL, so a caller with the allowlist on can batch-recheck.
        // A POLICY answer, not a failed call: fromError stays false.
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isFalse();
        assertThat(result.fromError()).isFalse();
    }

    @Test // a clean DENY_ALL (empty result) is fully supported — there was simply nothing to satisfy —
    // and NOT fromError: "unsatisfiable" is a real policy answer, so a subtree widening may still apply
    void emptyResult_isFullySupportedDeny() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"result\":{}}"));
        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isTrue();
        assertThat(result.fromError()).isFalse();
    }

    @Test // U8 — a reference that isn't input.resource.* → DENY_ALL
    void failClosed_onNonResourceReference() throws IOException {
        String dataRefExpr =
                "{\"index\":0,\"terms\":["
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"eq\"}]},"
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"data\"},"
                        + "{\"type\":\"string\",\"value\":\"foo\"}]},"
                        + "{\"type\":\"string\",\"value\":\"bar\"}]}";
        String body = "{\"result\":{\"queries\":[[" + dataRefExpr + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));
        assertThat(clientFor(base, "catalog").compile(categoryListContext()).decision())
                .isEqualTo(PartialResult.Decision.DENY_ALL);
    }

    @Test // U9 — request shape: POST /v1/compile, unknowns ["input.resource"], no resource in input, per-type query
    void requestShape_pinned() throws IOException {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        String base = startServer(ex -> {
            capturedPath.set(ex.getRequestURI().getPath());
            captured.set(ex.getRequestBody().readAllBytes());
            respond(ex, 200, "{\"result\":{\"queries\":[[]]}}");
        });

        clientFor(base, "catalog").compile(categoryListContext());

        assertThat(capturedPath.get()).isEqualTo("/v1/compile");
        JsonNode root = MAPPER.readTree(captured.get());
        assertThat(root.get("query").asText()).isEqualTo("data.catalog.category.filter == true");
        assertThat(root.get("unknowns").get(0).asText()).isEqualTo("input.resource");
        JsonNode input = root.get("input");
        assertThat(input.get("subject").get("id").asText()).isEqualTo("user-1");
        assertThat(input.get("action").asText()).isEqualTo("category:read");
        assertThat(input.get("role_definition").get("code").asText()).isEqualTo("catalog-viewer");
        // the resource is the unknown — it MUST be omitted from the compile input
        assertThat(input.has("resource")).isFalse();
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
