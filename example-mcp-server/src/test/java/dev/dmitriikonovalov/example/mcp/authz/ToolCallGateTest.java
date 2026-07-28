package dev.dmitriikonovalov.example.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilityUnavailableException;
import dev.dmitriikonovalov.example.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.example.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.example.mcp.identity.IdentityProperties;
import dev.dmitriikonovalov.example.mcp.tool.CatalogApiClient;
import dev.dmitriikonovalov.example.mcp.tool.CatalogApiErrorTranslator;
import dev.dmitriikonovalov.example.mcp.tool.CallerBearerSupplier;
import dev.dmitriikonovalov.example.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.example.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.HttpOpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClientConfig;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.net.InetSocketAddress;
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
 * I8–I15: the PEP, driven through the handler the MCP server actually invokes, against in-process
 * {@link HttpServer} stubs for OPA and the catalog API.
 *
 * <p>Every case asserts <strong>request counts on both stubs</strong>. That is how "the tool body never
 * ran" is proven rather than assumed: a denial that still reached the catalog service would be a gate
 * that logs rather than a gate that gates.
 */
class ToolCallGateTest {

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
            RoleDefinitionSupplier roles) {
        OpaClient opaClient = new HttpOpaClient(
                MAPPER,
                new ToolPolicyPathResolver(properties),
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

        CatalogApiClient catalogApi = new CatalogApiClient(
                MAPPER,
                new CallerBearerSupplier(),
                new CatalogApiErrorTranslator(MAPPER),
                catalogBaseUrl,
                Duration.ofMillis(500),
                Duration.ofSeconds(2));

        SyncToolSpecification specification = new SyncToolSpecification(
                new Tool(TOOL, null, "get one product", Map.of("type", "object"), null, null, null),
                (exchange, request) -> {
                    catalogApi.getJson("/api/v1/catalogs/c-1/categories/cat-1/products/p-1");
                    return new CallToolResult(
                            List.of(new TextContent(null, "ok", null)), Boolean.FALSE, null, Map.of());
                });

        return (ToolCallGate) ToolCallGate.gate(specification, authorizer).callHandler();
    }

    private static AgentCapabilitySupplier capability(AgentCapabilityProfile profile) {
        return actorId -> profile;
    }

    private static final AgentCapabilitySupplier READ_CAPABILITY = capability(
            new AgentCapabilityProfile(
                    java.util.Set.of("READ"), java.util.Set.of(TOOL), java.util.Set.of("view"), "medium"));

    private static final RoleDefinitionSupplier CEILING_SUPPLIER =
            (userId, resourceType, resourceId) -> Optional.of(CEILING);

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

    @Test // I10 — every OPA transport failure denies, and none reaches the catalog
    void deniesOnEveryOpaFailureMode() throws IOException {
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        String serverError = startOpa(exchange -> respond(exchange, 500, "boom"));
        assertThat(invoke(gateWith(serverError, catalog, READ_CAPABILITY, CEILING_SUPPLIER)).isError())
                .isTrue();
        opaStub.stop(0);

        // Connection refused — nothing listening.
        CallToolResult refused =
                invoke(gateWith("http://127.0.0.1:1", catalog, READ_CAPABILITY, CEILING_SUPPLIER));
        assertThat(refused.isError()).isTrue();
        assertThat(layerOf(refused)).isEqualTo("tool-gate");

        assertThat(catalogCalls).hasValue(0);
    }

    @Test // I11 — a malformed body, and a body with no allow binding, both deny
    void deniesOnAMalformedOrUnboundOpaResponse() throws IOException {
        String catalog = startCatalog();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        String malformed = startOpa(exchange -> respond(exchange, 200, "not json {{{"));
        assertThat(invoke(gateWith(malformed, catalog, READ_CAPABILITY, CEILING_SUPPLIER)).isError())
                .isTrue();
        opaStub.stop(0);

        String unbound = startOpa(exchange -> respond(exchange, 200, "{\"result\":{}}"));
        assertThat(invoke(gateWith(unbound, catalog, READ_CAPABILITY, CEILING_SUPPLIER)).isError())
                .isTrue();

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

        RoleDefinitionSupplier failing = (userId, type, id) -> {
            throw new RoleResolutionException("role source unavailable");
        };
        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, failing));

        assertThat(codeOf(result)).isEqualTo(ToolCallAuthorizer.CODE_CEILING_UNAVAILABLE);
        assertThat(opaCalls).hasValue(0);
        assertThat(catalogCalls).hasValue(0);
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
                        new ToolPolicyPathResolver(properties),
                        new OpaClientConfig(opa, Duration.ofSeconds(2), "allow")),
                properties);

        ToolAuthorizationDecision decision = authorizer.authorize("delete_everything");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo(ToolCallAuthorizer.CODE_UNDECLARED_TOOL);
        assertThat(opaCalls).hasValue(0);
    }

    @Test // I15 — the kill-switch: OFF skips the NARROWING, and the target-gate still denies
    void offIsNeverWiderThanOn() throws IOException {
        String opa = startOpa(ToolCallGateTest::denyResponse);
        // A catalog that refuses this principal on the actual resource.
        catalogStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalogStub.createContext("/", exchange -> {
            catalogCalls.incrementAndGet();
            try {
                respond(exchange, 403,
                        "{\"status\":403,\"errorCode\":\"ACCESS_DENIED\",\"detail\":\"nope\"}");
            } finally {
                exchange.close();
            }
        });
        catalogStub.start();
        String catalog = "http://127.0.0.1:" + catalogStub.getAddress().getPort();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        properties.getAgentGate().setEnabled(false);
        CallToolResult result = invoke(gateWith(opa, catalog, READ_CAPABILITY, CEILING_SUPPLIER));

        // The tool-gate no longer denies (OPA was not even asked)...
        assertThat(opaCalls).hasValue(0);
        // ...but the catalog service's own gate still refuses, surfaced as target-gate. OFF removes the
        // narrowing; it cannot grant beyond the principal.
        assertThat(result.isError()).isTrue();
        assertThat(layerOf(result)).isEqualTo("target-gate");
        assertThat(codeOf(result)).isEqualTo("ACCESS_DENIED");
        assertThat(catalogCalls).hasValue(1);
    }

    @Test // I14 — the two layers are distinguishable by the caller
    void namesTheTargetGateWhenTheCatalogDenies() throws IOException {
        String opa = startOpa(ToolCallGateTest::allowResponse);
        catalogStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalogStub.createContext("/", exchange -> {
            catalogCalls.incrementAndGet();
            try {
                respond(exchange, 403, "{\"status\":403,\"errorCode\":\"ACCESS_DENIED\"}");
            } finally {
                exchange.close();
            }
        });
        catalogStub.start();
        authenticate(Map.of("act_chain", List.of("agent-a")));

        CallToolResult result = invoke(gateWith(
                opa, "http://127.0.0.1:" + catalogStub.getAddress().getPort(),
                READ_CAPABILITY, CEILING_SUPPLIER));

        assertThat(result.isError()).isTrue();
        assertThat(layerOf(result)).isEqualTo("target-gate");
        assertThat(opaCalls).hasValue(1);
        assertThat(catalogCalls).hasValue(1);
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

        AbacContext context = authorizer.buildContext(DESCRIPTOR);

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

        AbacContext context = authorizer.buildContext(DESCRIPTOR);

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
