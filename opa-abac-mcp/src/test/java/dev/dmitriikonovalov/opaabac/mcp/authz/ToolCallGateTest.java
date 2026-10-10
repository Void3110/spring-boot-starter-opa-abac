package dev.dmitriikonovalov.opaabac.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;
import dev.dmitriikonovalov.opaabac.core.HttpOpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClientConfig;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityUnavailableException;
import dev.dmitriikonovalov.opaabac.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.IdentityProperties;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * I8–I13 and the switch's ceiling half: the PEP, driven through the handler the MCP server actually
 * invokes, against in-process {@link HttpServer} stubs for OPA and a downstream API. The tool body calls
 * the downstream stub with a plain JDK {@link HttpClient}; the cases that need the example's catalog
 * client (a {@code 403} relabelled as {@code target-gate}) live beside that client, in the example's
 * {@code CatalogToolGateTest}.
 *
 * <p>Every case asserts <strong>request counts on both stubs</strong>. That is how "the tool body never
 * ran" is proven rather than assumed: a denial that still reached the catalog service would be a gate
 * that logs rather than a gate that gates.
 */
class ToolCallGateTest {

    /**
     * The decorator's delegate: these cases decide {@code tool} contexts only, so a non-{@code tool}
     * context reaching the application's resolver is a test failure (the pre-decorator behavior).
     */
    private static final PolicyPathResolver TOOL_ONLY = context -> {
        throw new AssertionError("only tool contexts are decided here; got " + context.resource().type());
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CALLER_TOKEN = "caller.token.signature";
    private static final String TOOL = "get_product";

    private static final ToolDescriptor DESCRIPTOR =
            new ToolDescriptor(TOOL, "view", "READ", "product", java.util.Set.of("medium"));

    private HttpServer opaStub;
    private HttpServer catalogStub;
    private final AtomicInteger opaCalls = new AtomicInteger();
    private final AtomicInteger catalogCalls = new AtomicInteger();
    private final AtomicReference<String> callOrder = new AtomicReference<>("");
    /** The last body POSTed to the OPA stub — what the gate actually asked, not what it meant to. */
    private final AtomicReference<String> opaBody = new AtomicReference<>("");

    private final ToolAuthorizationProperties properties = new ToolAuthorizationProperties();

    @BeforeEach
    void bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + CALLER_TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        if (opaStub != null) {
            opaStub.stop(0);
        }
        if (catalogStub != null) {
            catalogStub.stop(0);
        }
    }

    // --- stubs ----------------------------------------------------------------------------------

    private String startOpa(StubHandler handler) throws IOException {
        opaStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        opaStub.createContext("/", exchange -> {
            opaCalls.incrementAndGet();
            callOrder.updateAndGet(order -> order + "O");
            try {
                // Capture the request body: several cells assert what was ASKED, not just the verdict —
                // a gate that sends the wrong input can still return the expected answer.
                opaBody.set(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        opaStub.start();
        return "http://127.0.0.1:" + opaStub.getAddress().getPort();
    }

    private String startCatalog() throws IOException {
        catalogStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalogStub.createContext("/", exchange -> {
            catalogCalls.incrementAndGet();
            callOrder.updateAndGet(order -> order + "C");
            try {
                respond(exchange, 200, "{\"id\":\"p-1\"}");
            } finally {
                exchange.close();
            }
        });
        catalogStub.start();
        return "http://127.0.0.1:" + catalogStub.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void allowResponse(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"result\":{\"allow\":true}}");
    }

    private static void denyResponse(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"result\":{\"allow\":false}}");
    }

    // --- collaborators --------------------------------------------------------------------------

    private void authenticate(Map<String, Object> attributes) {
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(
                new AbacContext.Subject("user-alice", List.of("product-viewer"), attributes)));
    }

    private static final RoleDefinition CEILING = new RoleDefinition(
            "type-level:product", Map.of(), Map.of("product", List.of("READ")), Map.of(), Map.of(), null);

    private ToolCallGate gateWith(
            String opaBaseUrl,
            String catalogBaseUrl,
            AgentCapabilitySupplier capabilities,
            PrincipalCeilingSupplier roles) {
        OpaClient opaClient = new HttpOpaClient(
                MAPPER,
                new ToolPolicyPathResolver(TOOL_ONLY, properties),
                new OpaClientConfig(opaBaseUrl, Duration.ofSeconds(2), "allow"));
        DelegationChainExtractor extractor =
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties());
        ToolCallAuthorizer authorizer = new ToolCallAuthorizer(
                new ToolRegistry(List.of(DESCRIPTOR)),
                extractor,
                capabilities,
                roles,
                opaClient,
                properties);

        SyncToolSpecification specification = new SyncToolSpecification(
                new Tool(TOOL, null, "get one product", Map.of("type", "object"), null, null, null),
                (exchange, request) -> {
                    downstreamGet(catalogBaseUrl + "/api/v1/catalogs/c-1/categories/cat-1/products/p-1");
                    return new CallToolResult(
                            List.of(new TextContent(null, "ok", null)), Boolean.FALSE, null, Map.of());
                });

        return (ToolCallGate) ToolCallGate.gate(specification, authorizer).callHandler();
    }

    /** The tool body's one downstream call — a plain GET, so the module needs no example client. */
    private static void downstreamGet(String url) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()) {
            client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException("downstream stub unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static AgentCapabilitySupplier capability(AgentCapabilityProfile profile) {
        return actorId -> profile;
    }

    private static final AgentCapabilitySupplier READ_CAPABILITY = capability(
            new AgentCapabilityProfile(
                    java.util.Set.of("READ"), java.util.Set.of(TOOL), java.util.Set.of("view"), "medium"));

    private static final PrincipalCeilingSupplier CEILING_SUPPLIER =
            (subject, resourceType) -> Optional.of(CEILING);

    private CallToolResult invoke(ToolCallGate gate) {
        return gate.apply(null, new CallToolRequest(TOOL, Map.of()));
    }

    private static String layerOf(CallToolResult result) {
        return String.valueOf(result.meta().get("layer"));
    }

    private static String codeOf(CallToolResult result) {
        return String.valueOf(result.meta().get("code"));
    }

    // --- the cases ------------------------------------------------------------------------------

    @Test // I8 — allow: the body runs, exactly one OPA call, and OPA PRECEDES the catalog
    void allowsAndRunsTheBodyAfterExactlyOneGateCall() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, CEILING_SUPPLIER));

        assertThat(result.isError()).isFalse();
        assertThat(opaCalls).hasValue(1);
        assertThat(catalogCalls).hasValue(1);
        assertThat(callOrder.get()).isEqualTo("OC"); // ordering asserted, not inferred
    }

    @Test // I9 — deny: the catalog stub is NEVER called, and the error names tool-gate
    void deniesWithoutEverReachingTheCatalog() throws IOException {
        String opa = startOpa(ToolCallGateTest::denyResponse);
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, CEILING_SUPPLIER));

        assertThat(result.isError()).isTrue();
        assertThat(layerOf(result)).isEqualTo("tool-gate");
        assertThat(codeOf(result)).isEqualTo(ToolCallAuthorizer.CODE_POLICY_DENIED);
        assertThat(catalogCalls).hasValue(0);
    }

    @Test // I10 — every OPA transport failure denies, and none reaches the catalog. ENGINE-ERRORS U30: the
    // denial says "the policy engine could not decide", not "the policy said no"
    void deniesOnEveryOpaFailureMode() throws IOException {
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        String serverError = startOpa(exchange -> respond(exchange, 500, "boom"));
        CallToolResult failed = invoke(gateWith(serverError, catalog, READ_CAPABILITY, CEILING_SUPPLIER));
        assertThat(failed.isError()).isTrue();
        assertThat(codeOf(failed)).isEqualTo(ToolCallAuthorizer.CODE_POLICY_UNAVAILABLE);
        opaStub.stop(0);

        // Connection refused — nothing listening.
        CallToolResult refused =
                invoke(gateWith("http://127.0.0.1:1", catalog, READ_CAPABILITY, CEILING_SUPPLIER));
        assertThat(refused.isError()).isTrue();
        assertThat(layerOf(refused)).isEqualTo("tool-gate");
        assertThat(codeOf(refused)).isEqualTo(ToolCallAuthorizer.CODE_POLICY_UNAVAILABLE);

        assertThat(catalogCalls).hasValue(0);
    }

    @Test // I11 — a malformed body, and a body with no allow binding, both deny — but only the malformed one
    // is "could not decide" (ENGINE-ERRORS U30): a loaded package whose allow is undefined is a policy deny
    void deniesOnAMalformedOrUnboundOpaResponse() throws IOException {
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        String malformed = startOpa(exchange -> respond(exchange, 200, "not json {{{"));
        CallToolResult unreadable = invoke(gateWith(malformed, catalog, READ_CAPABILITY, CEILING_SUPPLIER));
        assertThat(unreadable.isError()).isTrue();
        assertThat(codeOf(unreadable)).isEqualTo(ToolCallAuthorizer.CODE_POLICY_UNAVAILABLE);
        opaStub.stop(0);

        String unbound = startOpa(exchange -> respond(exchange, 200, "{\"result\":{}}"));
        CallToolResult undefined = invoke(gateWith(unbound, catalog, READ_CAPABILITY, CEILING_SUPPLIER));
        assertThat(undefined.isError()).isTrue();
        assertThat(codeOf(undefined)).isEqualTo(ToolCallAuthorizer.CODE_POLICY_DENIED);

        assertThat(catalogCalls).hasValue(0);
    }

    @Test // I12 — an unreadable identity denies BEFORE any OPA call is spent
    void deniesAMalformedIdentityWithoutCallingOpa() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", "not-an-array")); // the U8 malformed shape

        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, CEILING_SUPPLIER));

        assertThat(result.isError()).isTrue();
        assertThat(codeOf(result)).isEqualTo(ToolCallAuthorizer.CODE_IDENTITY_UNREADABLE);
        assertThat(opaCalls).hasValue(0);
        assertThat(catalogCalls).hasValue(0);
    }

    @Test // I13 — outage vs authoritative-empty: distinct codes, IDENTICAL caller-facing message
    void distinguishesACapabilityOutageFromZeroCapabilityOnlyInternally() throws IOException {
        String opa = startOpa(ToolCallGateTest::denyResponse);
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        AgentCapabilitySupplier outage = actorId -> {
            throw new AgentCapabilityUnavailableException("down", null);
        };
        CallToolResult onOutage = invoke(gateWith(opa, catalog, outage, CEILING_SUPPLIER));
        CallToolResult onEmpty = invoke(
                gateWith(opa, catalog, capability(AgentCapabilityProfile.empty()), CEILING_SUPPLIER));

        assertThat(onOutage.isError()).isTrue();
        assertThat(onEmpty.isError()).isTrue();
        assertThat(codeOf(onOutage)).isEqualTo(ToolCallAuthorizer.CODE_CAPABILITY_UNAVAILABLE);
        assertThat(codeOf(onEmpty)).isEqualTo(ToolCallAuthorizer.CODE_POLICY_DENIED);
        // No oracle: the two denials read identically to whoever is calling.
        assertThat(onOutage.content()).isEqualTo(onEmpty.content());
        assertThat(catalogCalls).hasValue(0);
    }

    @Test // an unresolvable ceiling is an outage too — deny, distinct code, no OPA call wasted
    void deniesWhenThePrincipalCeilingCannotBeResolved() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        PrincipalCeilingSupplier failing = (subject, type) -> {
            throw new RoleResolutionException("role source unavailable");
        };
        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, failing));

        assertThat(codeOf(result)).isEqualTo(ToolCallAuthorizer.CODE_CEILING_UNAVAILABLE);
        assertThat(opaCalls).hasValue(0);
        assertThat(catalogCalls).hasValue(0);
    }

    @Test // U5 — the ceiling outage is attributed by CALL SITE: an adopter's own family member counts too
    void attributesAnAdoptersOwnOutageSubtypeToTheCeiling() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        PrincipalCeilingSupplier failing = (subject, type) -> {
            throw new AdopterCeilingOutage("the adopter's directory timed out");
        };
        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, failing));

        // Not tool-gate-policy-unavailable: it is the ceiling that is unknown, not the policy engine.
        assertThat(codeOf(result)).isEqualTo(ToolCallAuthorizer.CODE_CEILING_UNAVAILABLE);
        assertThat(opaCalls).hasValue(0);
        assertThat(catalogCalls).hasValue(0);
    }

    /** An adopter's own "could not decide" family member — not one the library ships. */
    private static final class AdopterCeilingOutage extends DecisionIndeterminateException {

        private static final long serialVersionUID = 1L;

        AdopterCeilingOutage(String message) {
            super(message);
        }
    }

    @Test // an unauthenticated caller never reaches policy
    void deniesWhenNobodyIsAuthenticated() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        String catalog = startCatalog();
        SecurityContextHolder.clearContext();

        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, CEILING_SUPPLIER));

        assertThat(codeOf(result)).isEqualTo(ToolCallAuthorizer.CODE_UNAUTHENTICATED);
        assertThat(opaCalls).hasValue(0);
        assertThat(catalogCalls).hasValue(0);
    }

    @Test // an undeclared tool is unauthorizable, not unrestricted
    void deniesAnUndeclaredTool() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        authenticate(Map.of("act_chain", List.of("agent-a")));
        ToolCallAuthorizer authorizer = new ToolCallAuthorizer(
                new ToolRegistry(List.of()),
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                READ_CAPABILITY,
                CEILING_SUPPLIER,
                new HttpOpaClient(
                        MAPPER,
                        new ToolPolicyPathResolver(TOOL_ONLY, properties),
                        new OpaClientConfig(opa, Duration.ofSeconds(2), "allow")),
                properties);

        ToolAuthorizationDecision decision = authorizer.authorize("delete_everything");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo(ToolCallAuthorizer.CODE_UNDECLARED_TOOL);
        assertThat(opaCalls).hasValue(0);
    }

    @Test // the other half of the switch's contract: OFF still enforces the PRINCIPAL's ceiling
    void offStillDeniesWhenThePrincipalsOwnCeilingRefuses() throws IOException {
        // The policy refuses this principal even without any agent narrowing — the ceiling-only answer.
        String opa = startOpa(ToolCallGateTest::denyResponse);
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        properties.getAgentGate().setEnabled(false);
        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, CEILING_SUPPLIER));

        // Denied at the TOOL-gate, and the tool body never ran: turning the narrowing off is not a
        // bypass of the gate itself. Without this cell, "OFF is never wider than ON" rests entirely on
        // the downstream refusing for an independent reason.
        assertThat(result.isError()).isTrue();
        assertThat(layerOf(result)).isEqualTo("tool-gate");
        assertThat(codeOf(result)).isEqualTo("tool-gate-denied");
        assertThat(catalogCalls).hasValue(0);
    }

    @Test // I7 — the gate asks the right QUESTION, not just gets the right answer
    void sendsTheDeclaredToolAttributesAndTheDualIdentityToThePolicy() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        invoke(gateWith(opa, catalog, READ_CAPABILITY, CEILING_SUPPLIER));

        // The resource is the TOOL, carrying the statically declared attributes the policy reads; the
        // dual identity rides in subject.attributes. A gate that sent, say, the target type as the
        // resource type would still get "allow" from a stub and be wrong in production.
        assertThat(opaBody.get())
                .contains("\"type\":\"tool\"")
                .contains("\"id\":\"" + TOOL + "\"")
                .contains("\"category\":\"READ\"")
                .contains("\"target_type\":\"product\"")
                .contains("\"medium\"")
                .contains("\"action\":\"view\"")
                .contains("\"actor\":\"agent-a\"")
                .contains("\"agent_capability\"");
    }

    @Test // the labelling seam never invents a failure: a clean result passes through untouched
    void leavesASuccessfulResultAlone() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        authenticate(Map.of("act_chain", List.of("agent-a")));

        CallToolResult result = gateWith(opa, startCatalog(), READ_CAPABILITY, CEILING_SUPPLIER)
                .apply(null, new CallToolRequest(TOOL, Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat(result.meta()).isEmpty();
    }

    @Test // a human call (no actor claim) carries no agent attributes into the policy input
    void buildsAHumanContextWithoutAgentAttributes() {
        authenticate(Map.of());
        ToolCallAuthorizer authorizer = new ToolCallAuthorizer(
                new ToolRegistry(List.of(DESCRIPTOR)),
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                READ_CAPABILITY,
                CEILING_SUPPLIER,
                new DenyingOpaClient(),
                properties);

        AbacContext context = authorizer.buildContext(DESCRIPTOR, true);

        assertThat(context.subject().attributes()).doesNotContainKeys("actor", "chain", "agent_capability");
        assertThat(context.resource().type()).isEqualTo("tool");
        assertThat(context.resource().id()).isEqualTo(TOOL);
        assertThat(context.resource().attributes())
                .containsEntry("category", "READ")
                .containsEntry("target_type", "product")
                .containsEntry("risk_tags", List.of("medium"));
        assertThat(context.action()).isEqualTo("view");
        assertThat(context.roleDefinition()).isEqualTo(CEILING);
    }

    @Test // U7/U4 — an agent call carries actor, chain and capability, and nothing agent-ish leaks
    void buildsAnAgentContextWithTheDualIdentity() {
        authenticate(Map.of("act_chain", List.of("agent-a", "agent-b")));
        ToolCallAuthorizer authorizer = new ToolCallAuthorizer(
                new ToolRegistry(List.of(DESCRIPTOR)),
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                READ_CAPABILITY,
                CEILING_SUPPLIER,
                new DenyingOpaClient(),
                properties);

        AbacContext context = authorizer.buildContext(DESCRIPTOR, true);

        assertThat(context.subject().id()).isEqualTo("user-alice");
        assertThat(context.subject().attributes())
                .containsEntry("actor", "agent-a")
                .containsEntry("chain", List.of("agent-a", "agent-b"))
                .containsKey("agent_capability");
        // Nothing agent-related leaks into the resource — the tool is the resource, not the agent.
        assertThat(context.resource().attributes()).doesNotContainKeys("actor", "agent_capability");
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /**
     * A client that denies everything — used by the context-shape tests, which assert what the PEP
     * <em>builds</em> rather than what OPA answers. {@link OpaClient} has three methods, so it is not a
     * functional interface and cannot be a lambda.
     */
    private static final class DenyingOpaClient implements OpaClient {

        @Override
        public boolean allow(AbacContext context) {
            return false;
        }

        @Override
        public dev.dmitriikonovalov.opaabac.core.PartialResult compile(AbacContext context) {
            return dev.dmitriikonovalov.opaabac.core.PartialResult.error();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(context -> Boolean.FALSE).toList();
        }
    }
}
