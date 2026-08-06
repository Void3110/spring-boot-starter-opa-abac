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

    @Test // U7 — fail-closed on an unsafe resource type (would splice into the compile query)
    void failClosed_onTraversalResourceType() throws IOException {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        String base = startServer(ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "{\"result\":{\"queries\":[[]]}}");
        });

        AbacContext.Subject subject = new AbacContext.Subject("user-1", List.of("catalog-viewer"), Map.of());
        AbacContext context = new AbacContext(
                subject,
                "category:read",
                new AbacContext.Resource("category/../admin", null, Map.of()),
                null,
                Map.of());

        PartialResult result = clientFor(base, "catalog").compile(context);
        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fromError()).isTrue();
        assertThat(hits.get()).isZero();
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
            } catch (InterruptedException _) {
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

    @Test // review fix 2026-06-12 — a NEGATED equality folds into the operator (eq → neq): still a
    // clean, fully-supported residual.
    void conditional_negatedEqFoldsToNeq() throws IOException {
        String negatedEq = COND_REGION_EQ_EMEA.replace(
                "{\"index\":1,\"terms\"", "{\"index\":1,\"negated\":true,\"terms\"");
        String body = "{\"result\":{\"queries\":[[" + negatedEq + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.CONDITIONAL);
        Condition c = result.clauses().get(0).conditions().get(0);
        assertThat(c.operator()).isEqualTo(Condition.Operator.NEQ);
        assertThat(c.path()).isEqualTo("tags.region");
        assertThat(c.value()).isEqualTo("emea");
    }

    @Test // review fix 2026-06-12 — the fail-closed seat for `not filter_list_denied` (Phase 6.5): a
    // NEGATED membership is not representable in the closed predicate set → DENY_ALL, not fully
    // supported (the caller's allowlist path batch-rechecks), and NOT fromError (a policy answer).
    void failClosed_onNegatedMembership() throws IOException {
        String negatedIn =
                "{\"index\":0,\"negated\":true,\"terms\":["
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"internal\"},"
                        + "{\"type\":\"string\",\"value\":\"member_2\"}]},"
                        + "{\"type\":\"string\",\"value\":\"list\"},"
                        + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                        + "{\"type\":\"string\",\"value\":\"resource\"},"
                        + "{\"type\":\"string\",\"value\":\"attributes\"},"
                        + "{\"type\":\"string\",\"value\":\"denied\"}]}]}";
        String body = "{\"result\":{\"queries\":[[" + negatedIn + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isFalse();
        assertThat(result.fromError()).isFalse();
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
        assertThat(root.get("query").asString()).isEqualTo("data.catalog.category.filter == true");
        assertThat(root.get("unknowns").get(0).asString()).isEqualTo("input.resource");
        JsonNode input = root.get("input");
        assertThat(input.get("subject").get("id").asString()).isEqualTo("user-1");
        assertThat(input.get("action").asString()).isEqualTo("category:read");
        assertThat(input.get("role_definition").get("code").asString()).isEqualTo("catalog-viewer");
        // the resource is the unknown — it MUST be omitted from the compile input
        assertThat(input.has("resource")).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Multi-type folding (2026-08-06). A role granting several resource types compiles to a DNF with
    // disjuncts for EVERY granted type, each guarded by eq(<type>, input.resource.type) — the REAL
    // /v1/compile shape for the e2e filter matrix's reader roles (catalog+category+product READ),
    // verified against OPA 1.x on the rig. Before the fold, the foreign-type binding poisoned the
    // whole residual → every multi-type role silently degraded to the allowlist-batch path.
    // ---------------------------------------------------------------------------------------------

    // The foreign resource-type binding {input.resource.type == "product"} (parsing for "category").
    private static final String PRODUCT_TYPE_BINDING =
            "{\"index\":0,\"terms\":["
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"eq\"}]},"
                    + "{\"type\":\"string\",\"value\":\"product\"},"
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                    + "{\"type\":\"string\",\"value\":\"resource\"},"
                    + "{\"type\":\"string\",\"value\":\"type\"}]}]}";

    // The membership residual {"emea" in input.resource.attributes.region} (literal LEFT → CONTAINS).
    private static final String COND_REGION_CONTAINS_EMEA =
            "{\"index\":1,\"terms\":["
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"internal\"},"
                    + "{\"type\":\"string\",\"value\":\"member_2\"}]},"
                    + "{\"type\":\"string\",\"value\":\"emea\"},"
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                    + "{\"type\":\"string\",\"value\":\"resource\"},"
                    + "{\"type\":\"string\",\"value\":\"attributes\"},"
                    + "{\"type\":\"string\",\"value\":\"region\"}]}]}";

    // An unsupported operator expression (gt on a tag attribute).
    private static final String UNSUPPORTED_GT_EXPR =
            "{\"index\":1,\"terms\":["
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"gt\"}]},"
                    + "{\"type\":\"ref\",\"value\":[{\"type\":\"var\",\"value\":\"input\"},"
                    + "{\"type\":\"string\",\"value\":\"resource\"},"
                    + "{\"type\":\"string\",\"value\":\"attributes\"},"
                    + "{\"type\":\"string\",\"value\":\"score\"}]},"
                    + "{\"type\":\"number\",\"value\":5}]}";

    @Test // MT1 — the real multi-type shape: foreign-type disjuncts FOLD AWAY, same-type ones survive →
    // CONDITIONAL and fully SQL (the pure-SQL path), not the pre-fix silent batch degradation.
    void conditional_multiTypeRole_foreignTypeDisjunctsFold() throws IOException {
        String body = "{\"result\":{\"queries\":["
                + "[" + TYPE_BINDING + "," + COND_REGION_EQ_EMEA + "],"
                + "[" + TYPE_BINDING + "," + COND_REGION_CONTAINS_EMEA + "],"
                + "[" + PRODUCT_TYPE_BINDING + "," + COND_REGION_EQ_EMEA + "],"
                + "[" + PRODUCT_TYPE_BINDING + "," + COND_REGION_CONTAINS_EMEA + "]"
                + "]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.CONDITIONAL);
        assertThat(result.fullySupported()).isTrue();
        assertThat(result.clauses()).hasSize(2); // the two product-bound disjuncts folded away
        Condition eq = result.clauses().get(0).conditions().get(0);
        assertThat(eq.operator()).isEqualTo(Condition.Operator.EQ);
        assertThat(eq.path()).isEqualTo("tags.region");
        Condition contains = result.clauses().get(1).conditions().get(0);
        assertThat(contains.operator()).isEqualTo(Condition.Operator.CONTAINS);
        assertThat(contains.path()).isEqualTo("tags.region");
    }

    @Test // MT2 — EVERY disjunct is foreign-type (the role has no direct grant on this type) → NOT a
    // clean deny: the compiled filter says nothing about this type, but the full policy might (an
    // inheritable ancestor grant the filter rule does not model, or a type-vocabulary drift). Deny,
    // flagged not-fully-SQL, so the caller's hierarchy-aware batch re-check decides — the adversarial
    // review (2026-08-06) confirmed a clean DENY_ALL here empties lists whose rows a single-GET allows.
    void unsupported_whenEveryDisjunctIsForeignType() throws IOException {
        String body = "{\"result\":{\"queries\":["
                + "[" + PRODUCT_TYPE_BINDING + "," + COND_REGION_EQ_EMEA + "],"
                + "[" + PRODUCT_TYPE_BINDING + "," + COND_REGION_CONTAINS_EMEA + "]"
                + "]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isFalse();
        assertThat(result.fromError()).isFalse();
    }

    @Test // MT3 — a foreign-type disjunct folds even when it ALSO carries an unsupported expression:
    // X AND false = false regardless of the siblings, so the contradiction wins over the poison.
    void foreignTypeDisjunctFolds_evenWithUnsupportedSibling() throws IOException {
        String body = "{\"result\":{\"queries\":["
                + "[" + PRODUCT_TYPE_BINDING + "," + UNSUPPORTED_GT_EXPR + "],"
                + "[" + TYPE_BINDING + "," + COND_REGION_EQ_EMEA + "]"
                + "]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.CONDITIONAL);
        assertThat(result.fullySupported()).isTrue();
        assertThat(result.clauses()).hasSize(1);
    }

    @Test // MT4 — the poison is PRESERVED where it belongs: an unsupported expression in a SAME-type
    // disjunct still denies not-fully-supported (the caller's allowlist path batch-rechecks).
    void failClosed_unsupportedInSameTypeDisjunct_stillPoisons() throws IOException {
        String body = "{\"result\":{\"queries\":[["
                + TYPE_BINDING + "," + UNSUPPORTED_GT_EXPR
                + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isFalse();
        assertThat(result.fromError()).isFalse();
    }

    @Test // MT5 — only the exact EQ-on-type shape folds: a NEGATED type binding (neq) still poisons —
    // the parser does not reason about what a negated binding implies for this table.
    void failClosed_negatedTypeBinding_stillPoisons() throws IOException {
        String negatedTypeBinding = PRODUCT_TYPE_BINDING.replace(
                "{\"index\":0,\"terms\"", "{\"index\":0,\"negated\":true,\"terms\"");
        String body = "{\"result\":{\"queries\":[["
                + negatedTypeBinding + "," + COND_REGION_EQ_EMEA
                + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isFalse();
        assertThat(result.fromError()).isFalse();
    }

    @Test // MT6 — the deferred-poison scan: an unsupported expression BEFORE the foreign-type binding in
    // the same conjunction. The loop must keep scanning past the unsupported expression and still fold
    // the disjunct (X AND false = false); a regression to the pre-fold immediate-poison return would
    // pass every other MT test but fail this one.
    void foreignTypeDisjunctFolds_whenUnsupportedComesFirst() throws IOException {
        String body = "{\"result\":{\"queries\":["
                + "[" + UNSUPPORTED_GT_EXPR + "," + PRODUCT_TYPE_BINDING + "],"
                + "[" + TYPE_BINDING + "," + COND_REGION_EQ_EMEA + "]"
                + "]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.CONDITIONAL);
        assertThat(result.fullySupported()).isTrue();
        assertThat(result.clauses()).hasSize(1);
    }

    @Test // MT7 — the curator shape: foreign disjuncts folding must not disturb a same-type disjunct
    // that is PURE tautology (an unconditional grant on this type) → ALLOW_ALL, not deny.
    void allowAll_foldedForeignDisjunctsPlusUnconditionalSameType() throws IOException {
        String body = "{\"result\":{\"queries\":["
                + "[" + PRODUCT_TYPE_BINDING + "],"
                + "[" + TYPE_BINDING + "]"
                + "]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.ALLOW_ALL);
    }

    @Test // MT8a — the fold's literal boundary is STRING-ONLY: a non-string literal on type (a number)
    // is not something the parser will vouch a contradiction for → poison, not fold. Pins the
    // deliberate `lit instanceof String` tightening (2026-08-06): loosening it to any non-equal
    // literalValue result would fold shapes the parser cannot positively prove contradictory.
    void failClosed_nonStringTypeLiteral_stillPoisons() throws IOException {
        String numberTypeBinding = PRODUCT_TYPE_BINDING.replace(
                "{\"type\":\"string\",\"value\":\"product\"}", "{\"type\":\"number\",\"value\":5}");
        String body = "{\"result\":{\"queries\":["
                + "[" + numberTypeBinding + "],"
                + "[" + TYPE_BINDING + "," + COND_REGION_EQ_EMEA + "]"
                + "]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isFalse();
    }

    @Test // MT8b — an UNRECOGNIZED literal on type (a set) likewise poisons: the parser only folds what
    // it can positively prove contradictory (a definite, unequal STRING literal).
    void failClosed_unrecognizedTypeLiteral_stillPoisons() throws IOException {
        String setTypeBinding = PRODUCT_TYPE_BINDING.replace(
                "{\"type\":\"string\",\"value\":\"product\"}",
                "{\"type\":\"set\",\"value\":[{\"type\":\"string\",\"value\":\"product\"}]}");
        String body = "{\"result\":{\"queries\":[[" + setTypeBinding + "]]}}";
        String base = startServer(ex -> respond(ex, 200, body));

        PartialResult result = clientFor(base, "catalog").compile(categoryListContext());

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isFalse();
    }

    @Test // MT9 — a NULL parser resource type (reachable: HttpOpaClient passes null when the context has
    // no resource) cannot judge foreign-vs-matching → EVERY type binding poisons, none folds. Pins the
    // guard against a "null-safe cleanup" (Objects.equals) that would silently fold everything.
    void failClosed_nullResourceType_typeBindingPoisons() {
        String body = "{\"result\":{\"queries\":[[" + PRODUCT_TYPE_BINDING + "]]}}";

        PartialResult result = new CompileResponseParser(null).parse(MAPPER.readTree(body));

        assertThat(result.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(result.fullySupported()).isFalse();
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
