package dev.dmitriikonovalov.example.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.mcp.tool.CallerBearerSupplier;
import dev.dmitriikonovalov.example.mcp.tool.CatalogApiClient;
import dev.dmitriikonovalov.example.mcp.tool.CatalogApiErrorTranslator;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.HttpOpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClientConfig;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolAuthorizationProperties;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallGate;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolPolicyPathResolver;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.opaabac.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.IdentityProperties;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolInvocationException;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
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
 * I14/I15: the cases of the tool-gate's PEP that need <strong>this example's catalog client</strong> — a
 * catalog {@code 403} becomes a {@code target-gate} denial only through {@link CatalogApiClient} and
 * {@link CatalogApiErrorTranslator}, so these three stay here while the rest of the gate's cases moved to
 * the {@code opa-abac-mcp} module ({@code ToolCallGateTest} there). Driven through the handler the MCP
 * server actually invokes, against in-process {@link HttpServer} stubs for OPA and the catalog API.
 *
 * <p>Every case asserts <strong>request counts on both stubs</strong>. That is how "the tool body never
 * ran" is proven rather than assumed: a denial that still reached the catalog service would be a gate
 * that logs rather than a gate that gates.
 */
class CatalogToolGateTest {

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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void allowResponse(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"result\":{\"allow\":true}}");
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

    /**
     * A gate whose delegate behaves like Spring AI's annotation-scanned handler: it CATCHES the tool
     * body's exception and returns a flat, unlabelled error result rather than letting it propagate.
     */
    private ToolCallGate swallowingGate(String opaBaseUrl, String catalogBaseUrl) {
        ToolCallGate direct = gateWith(opaBaseUrl, catalogBaseUrl, READ_CAPABILITY, CEILING_SUPPLIER);
        SyncToolSpecification swallowing = new SyncToolSpecification(
                new Tool(TOOL, null, "get one product", Map.of("type", "object"), null, null, null),
                (exchange, request) -> {
                    try {
                        return direct.apply(exchange, request);
                    } catch (ToolInvocationException e) {
                        return new CallToolResult(
                                List.of(new TextContent(null,
                                        "Error invoking method: getProduct\n" + e.getMessage(), null)),
                                Boolean.TRUE, null, Map.of());
                    }
                });
        return (ToolCallGate) ToolCallGate.gate(swallowing, authorizerFor(opaBaseUrl)).callHandler();
    }

    private ToolCallAuthorizer authorizerFor(String opaBaseUrl) {
        return new ToolCallAuthorizer(
                new ToolRegistry(List.of(DESCRIPTOR)),
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                READ_CAPABILITY,
                CEILING_SUPPLIER,
                new HttpOpaClient(
                        MAPPER,
                        new ToolPolicyPathResolver(TOOL_ONLY, properties),
                        new OpaClientConfig(opaBaseUrl, Duration.ofSeconds(2), "allow")),
                properties);
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

    @Test // I15 — the kill-switch: OFF skips the NARROWING, and the target-gate still denies
    void offIsNeverWiderThanOn() throws IOException {
        // The policy's HUMAN branch permits this principal — the ceiling-only answer the switch leaves
        // in place. The refusal below therefore has to come from the other layer, which is the point.
        String opa = startOpa(CatalogToolGateTest::allowResponse);
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

        // OFF drops the capability conjunct and NOTHING else: the gate still asks, with a context that
        // is an ordinary human principal's. (Deep review 2026-07-31 — this cell previously asserted
        // `opaCalls == 0`, pinning a short-circuit that made the switch disable call-time enforcement
        // outright and left the roster, which never short-circuited, NARROWER than the call path.)
        assertThat(opaCalls).hasValue(1);
        assertThat(opaBody.get())
                .as("the agent identity and capability must be absent from a gate-off context")
                .doesNotContain("\"actor\"")
                .doesNotContain("\"agent_capability\"")
                .doesNotContain("\"chain\"");
        // The policy's human branch allows it, and the catalog service's own gate then refuses on the
        // actual resource — surfaced as target-gate. OFF cannot grant beyond the principal.
        assertThat(result.isError()).isTrue();
        assertThat(layerOf(result)).isEqualTo("target-gate");
        assertThat(codeOf(result)).isEqualTo("ACCESS_DENIED");
        assertThat(catalogCalls).hasValue(1);
    }

    @Test // I14 — the two layers are distinguishable by the caller
    void namesTheTargetGateWhenTheCatalogDenies() throws IOException {
        String opa = startOpa(CatalogToolGateTest::allowResponse);
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

    @Test // I14, through the SWALLOWING delegate the real annotation scanner installs (rig-found)
    void namesTheTargetGateEvenWhenTheDelegateSwallowsTheException() throws IOException {
        String opa = startOpa(CatalogToolGateTest::allowResponse);
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

        // The case above lets the tool body's exception propagate straight into the gate — which is
        // what a HAND-BUILT specification does, and is why it passed while the rig showed an
        // unlabelled target-gate denial. Spring AI's annotation-scanned handler CATCHES whatever the
        // @McpTool method throws and returns a flat error result instead. This fixture reproduces
        // that shape, so the labelling seam is pinned by the same invocation contract the SDK uses.
        CallToolResult result = swallowingGate(
                opa, "http://127.0.0.1:" + catalogStub.getAddress().getPort())
                .apply(null, new CallToolRequest(TOOL, Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(layerOf(result))
                .as("a target-gate denial must stay distinguishable from a transport fault")
                .isEqualTo("target-gate");
        assertThat(catalogCalls).hasValue(1);
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
