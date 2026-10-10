package dev.dmitriikonovalov.opaabac.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.HttpOpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClientConfig;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.opaabac.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.IdentityProperties;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * U30 (MCP-AUTH-LIBRARY T7, ADR 0036 §7): every tool-gate input is marked with the input version — the
 * single decision's {@code input.environment.schema}, and that of <strong>every</strong> item of a roster
 * batch — asserted on the bytes the shipped {@link HttpOpaClient} actually POSTs (an in-process
 * {@link HttpServer} records each body), not on an object the test built itself.
 */
class ToolGateInputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<ToolDescriptor> DESCRIPTORS = List.of(
            new ToolDescriptor("list_catalogs", "list", "READ", "catalog", Set.of("low")),
            new ToolDescriptor("get_catalog", "view", "READ", "catalog", Set.of("low")),
            new ToolDescriptor("get_product", "view", "READ", "product", Set.of("medium")));
    private static final RoleDefinition CEILING = new RoleDefinition(
            "type-level", Map.of(), Map.of("catalog", List.of("READ")), Map.of(), Map.of(), null);

    private final ToolAuthorizationProperties properties = new ToolAuthorizationProperties();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer opa;

    @BeforeEach
    void startOpa() throws IOException {
        opa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        opa.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String answer = exchange.getRequestURI().getPath().endsWith("/bulk")
                    ? "{\"result\":[true,true,true]}"
                    : "{\"result\":{\"allow\":true}}";
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        opa.start();
    }

    @AfterEach
    void stop() {
        opa.stop(0);
        SecurityContextHolder.clearContext();
    }

    private ToolCallAuthorizer authorizer() {
        HttpOpaClient client = new HttpOpaClient(
                MAPPER,
                new ToolPolicyPathResolver(context -> {
                    throw new AssertionError("only tool contexts are decided here: " + context.resource().type());
                }, properties),
                new OpaClientConfig("http://127.0.0.1:" + opa.getAddress().getPort(), Duration.ofSeconds(2), "allow"));
        return new ToolCallAuthorizer(
                new ToolRegistry(DESCRIPTORS),
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                actor -> new AgentCapabilityProfile(Set.of("READ"), Set.of("get_catalog"), Set.of("view"), "low"),
                (subject, type) -> Optional.of(CEILING),
                client,
                properties);
    }

    private static void authenticate(Map<String, Object> attributes) {
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(
                new AbacContext.Subject("user-alice", List.of(), attributes)));
    }

    @Test // U30 — the single decision's input carries the v1 marker (human and agent alike)
    void aSingleDecisionIsMarked() {
        authenticate(Map.of());
        authorizer().authorize("get_catalog");
        authenticate(Map.of("act_chain", List.of("agent-a")));
        authorizer().authorize("get_catalog");

        assertThat(paths).containsExactly("/v1/data/agent_tools", "/v1/data/agent_tools");
        for (String body : bodies) {
            assertThat(MAPPER.readTree(body).path("input").path("environment").path("schema").asString())
                    .isEqualTo(ToolGateInput.SCHEMA_V1)
                    .isEqualTo("opa-abac.mcp.tool-gate/v1");
        }
    }

    @Test // U30 — EVERY item of a roster batch carries it
    void everyRosterBatchItemIsMarked() {
        authenticate(Map.of("act_chain", List.of("agent-a")));
        ToolRosterFilter roster =
                new ToolRosterFilter(new ToolRegistry(DESCRIPTORS), authorizer(), authorizerClient(), properties);

        roster.decide();

        assertThat(paths).containsExactly("/v1/data/agent_tools/bulk");
        JsonNode items = MAPPER.readTree(bodies.get(0)).path("input").path("items");
        assertThat(items.size()).as("one item per declared tool").isEqualTo(DESCRIPTORS.size());
        for (JsonNode item : items) {
            assertThat(item.path("environment").path("schema").asString()).isEqualTo(ToolGateInput.SCHEMA_V1);
        }
    }

    @Test // the marker is the environment's only content — nothing else rides along unversioned
    void theEnvironmentCarriesTheMarkerOnly() {
        assertThat(ToolGateInput.environment()).containsExactly(Map.entry("schema", ToolGateInput.SCHEMA_V1));
    }

    /** The roster's own client — the same shipped HttpOpaClient against the same stub. */
    private HttpOpaClient authorizerClient() {
        return new HttpOpaClient(
                MAPPER,
                new ToolPolicyPathResolver(context -> {
                    throw new AssertionError("only tool contexts are decided here");
                }, properties),
                new OpaClientConfig("http://127.0.0.1:" + opa.getAddress().getPort(), Duration.ofSeconds(2), "allow"));
    }
}
