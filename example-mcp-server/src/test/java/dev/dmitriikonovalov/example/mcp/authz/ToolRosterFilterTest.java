package dev.dmitriikonovalov.example.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilityUnavailableException;
import dev.dmitriikonovalov.example.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.example.mcp.identity.IdentityProperties;
import dev.dmitriikonovalov.example.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.example.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.HttpOpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClientConfig;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * I16–I21 and I25–I31: the roster pre-flight, driven through the real {@link HttpOpaClient} against an
 * in-process OPA stub.
 *
 * <h2>Why the stub answers both endpoints from one rule</h2>
 * The stub reads the tool name out of the request body and applies a single {@code allowedTools} set to
 * both {@code /v1/data/agent_tools} (one decision) and {@code /v1/data/agent_tools/bulk} (the batch). That
 * is what makes <strong>I16 an assertion rather than a tautology</strong>: if the two paths were given
 * separate canned answers, "the listed set equals the callable set" would be true by construction of the
 * fixture instead of by construction of the code.
 */
class ToolRosterFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LIST_CATALOGS = "list_catalogs";
    private static final String GET_CATALOG = "get_catalog";
    private static final String LIST_CATEGORIES = "list_categories";
    private static final String GET_PRODUCT = "get_product";

    /** Declaration order — the roster's contexts are built in exactly this order. */
    private static final List<ToolDescriptor> DESCRIPTORS = List.of(
            new ToolDescriptor(LIST_CATALOGS, "list", "READ", "catalog", Set.of("low")),
            new ToolDescriptor(GET_CATALOG, "view", "READ", "catalog", Set.of("low")),
            new ToolDescriptor(LIST_CATEGORIES, "list", "READ", "category", Set.of("low")),
            new ToolDescriptor(GET_PRODUCT, "view", "READ", "product", Set.of("medium")));

    /** The 2-of-4 fixture: this persona may call the two catalog tools and nothing else. */
    private static final Set<String> CAPABLE_OF = Set.of(LIST_CATALOGS, GET_CATALOG);

    private static final RoleDefinition CEILING = new RoleDefinition(
            "type-level",
            Map.of(),
            Map.of("catalog", List.of("READ"), "category", List.of("READ"), "product", List.of("READ")),
            Map.of(), Map.of(), null);

    private static final RoleDefinitionSupplier CEILING_SUPPLIER =
            (userId, resourceType, resourceId) -> Optional.of(CEILING);

    private static final AgentCapabilitySupplier CAPABILITY_SUPPLIER = actorId ->
            new AgentCapabilityProfile(Set.of("READ"), CAPABLE_OF, Set.of("view", "list"), "low");

    private final ToolAuthorizationProperties properties = new ToolAuthorizationProperties();
    private final ToolRegistry registry = new ToolRegistry(DESCRIPTORS);

    private HttpServer opaStub;
    private final AtomicInteger opaCalls = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void bindTurn() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        if (opaStub != null) {
            opaStub.stop(0);
        }
    }

    // --- I16 / I17 : the cut, and the single round-trip ------------------------------------------

    @Test // I16 — the listed set equals the callable set EXACTLY, by name
    void theListedSetEqualsTheCallableSetByName() throws IOException {
        String opa = startPolicyStub(CAPABLE_OF);
        agentCaller();
        ToolCallAuthorizer authorizer = authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER);

        RosterDecision decision = filter(authorizer, opa).decide();
        List<String> listed = namesOf(ToolRosterFilter.apply(decision, fullRoster()));

        List<String> callable = DESCRIPTORS.stream()
                .map(ToolDescriptor::name)
                .filter(name -> authorizer.authorize(name).allowed())
                .toList();

        assertThat(listed)
                .as("the roster must advertise exactly what the call-time gate will allow")
                .containsExactlyInAnyOrderElementsOf(callable)
                .containsExactlyInAnyOrder(LIST_CATALOGS, GET_CATALOG);
        assertThat(listed).doesNotContain(LIST_CATEGORIES, GET_PRODUCT);
    }

    @Test // I17 — ONE batch round-trip for N tools, not N calls
    void asksOpaExactlyOnceForTheWholeRoster() throws IOException {
        String opa = startPolicyStub(CAPABLE_OF);
        agentCaller();

        filter(authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER), opa).decide();

        assertThat(opaCalls.get()).isEqualTo(1);
        assertThat(lastPath.get()).isEqualTo("/v1/data/agent_tools/bulk");
    }

    // --- I18 / I19 : the empty roster is an ANSWER, not a degradation ----------------------------

    @Test // I18 — a dead PDP: the roster is empty AND every call denies. The pair is the point.
    void aDeadPdpYieldsAnEmptyRosterAndEveryCallDenies() throws IOException {
        String dead = startStub(exchange -> respond(exchange, 503, "upstream is down"));
        agentCaller();
        ToolCallAuthorizer authorizer = authorizer(dead, CAPABILITY_SUPPLIER, CEILING_SUPPLIER);

        RosterDecision decision = filter(authorizer, dead).decide();

        assertThat(decision.isUnfiltered())
                .as("all-false is authoritative — it must NOT be read as a failure to decide")
                .isFalse();
        assertThat(namesOf(ToolRosterFilter.apply(decision, fullRoster()))).isEmpty();
        assertThat(DESCRIPTORS.stream().map(ToolDescriptor::name))
                .allSatisfy(name -> assertThat(authorizer.authorize(name).allowed())
                        .as("call-time gate for %s during the same outage", name)
                        .isFalse());
    }

    @Test // I18 — the other two dead-PDP edges, asserted separately
    void aTimingOutOrRefusedPdpAlsoYieldsAnEmptyRoster() throws IOException {
        String stalling = startStub(exchange -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"result\":[true,true,true,true]}");
        });
        agentCaller();
        OpaClient impatient = opaClient(stalling, Duration.ofMillis(250));

        assertThat(rosterNames(new ToolRosterFilter(
                registry, authorizer(stalling, CAPABILITY_SUPPLIER, CEILING_SUPPLIER),
                impatient, properties)))
                .as("a timeout is normalised to all-false by the shipped client")
                .isEmpty();

        // Connection refused: a port nothing is listening on.
        agentCaller();
        assertThat(rosterNames(filter(
                authorizer("http://127.0.0.1:1", CAPABILITY_SUPPLIER, CEILING_SUPPLIER),
                "http://127.0.0.1:1")))
                .isEmpty();
    }

    @Test // I19 — a zero-capability agent gets the SAME empty roster; the two are indistinguishable
    void aZeroCapabilityAgentGetsTheSameEmptyRosterAsADeadPdp() throws IOException {
        String opa = startPolicyStub(Set.of());
        agentCaller();

        RosterDecision decision =
                filter(authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER), opa).decide();

        assertThat(decision.isUnfiltered()).isFalse();
        assertThat(namesOf(ToolRosterFilter.apply(decision, fullRoster()))).isEmpty();
    }

    // --- I20 / I21 : the kill-switch, and the hint that is never a grant -------------------------

    @Test // I20 — roster-filter OFF is the unfiltered list, and call-time enforcement is unchanged
    void theRosterKillSwitchOffServesTheUnfilteredListWithTheGateUntouched() throws IOException {
        String opa = startPolicyStub(CAPABLE_OF);
        agentCaller();
        properties.getRosterFilter().setEnabled(false);
        ToolCallAuthorizer authorizer = authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER);

        // OFF means the installer never runs, so the served list is the delegate's, untouched — the
        // same bytes the outside-the-batch degradation path serves.
        ListToolsResult served = ToolRosterFilter.apply(RosterDecision.unfiltered(), fullRoster());

        assertThat(namesOf(served))
                .containsExactly(LIST_CATALOGS, GET_CATALOG, LIST_CATEGORIES, GET_PRODUCT);
        assertThat(served).isSameAs(fullRosterInstance);
        assertThat(authorizer.authorize(GET_PRODUCT).allowed())
                .as("no property disables call-time enforcement")
                .isFalse();
    }

    @Test // I21 — listed, then revoked: the call still denies. The list was a hint, never a grant.
    void aListedButRevokedToolIsStillDeniedAtCallTime() throws IOException {
        AtomicReference<Set<String>> live = new AtomicReference<>(CAPABLE_OF);
        String opa = startStub(exchange -> answerFromPolicy(exchange, live.get()));
        agentCaller();
        ToolCallAuthorizer authorizer = authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER);

        // Turn 1: the tool is listed.
        assertThat(rosterNames(filter(authorizer, opa))).contains(GET_CATALOG);

        // The capability is revoked between turns; a new turn gets a fresh memo.
        live.set(Set.of());
        newTurn();

        assertThat(authorizer.authorize(GET_CATALOG).allowed())
                .as("call-time enforcement always runs, even for a tool listed a moment ago")
                .isFalse();
    }

    // --- I25 : two concurrent callers never see each other's roster ------------------------------

    @Test // I25 — different identities, concurrently: different rosters, no cross-leak
    void twoConcurrentCallersGetTheirOwnRosterAndNeverTheOthers() throws Exception {
        // The stub answers from the SUBJECT in the request, so a leak would show up as the wrong cut.
        String opa = startStub(exchange -> answerPerSubject(exchange,
                Map.of("user-wide", Set.of(LIST_CATALOGS, GET_CATALOG, LIST_CATEGORIES, GET_PRODUCT),
                        "user-narrow", Set.of(LIST_CATALOGS))));

        CountDownLatch bothReady = new CountDownLatch(2);
        AtomicReference<List<String>> wide = new AtomicReference<>();
        AtomicReference<List<String>> narrow = new AtomicReference<>();

        Thread a = callerThread("user-wide", opa, bothReady, wide);
        Thread b = callerThread("user-narrow", opa, bothReady, narrow);
        a.start();
        b.start();
        a.join(10_000);
        b.join(10_000);

        assertThat(wide.get()).containsExactlyInAnyOrder(
                LIST_CATALOGS, GET_CATALOG, LIST_CATEGORIES, GET_PRODUCT);
        assertThat(narrow.get()).containsExactly(LIST_CATALOGS);
        assertThat(narrow.get())
                .as("the narrow caller must never observe the wide caller's roster")
                .doesNotContain(GET_PRODUCT);
    }

    // --- I26 / I29 : one rule for humans and agents; the gate-off cut ----------------------------

    @Test // I26 — a human token (no actor claim) gets the ceiling-only roster from the same rule
    void aHumanTokenGetsTheCeilingOnlyRosterFromTheSameRule() throws IOException {
        // The stub allows everything the CEILING covers; the human has no capability to narrow by.
        String opa = startPolicyStub(Set.of(LIST_CATALOGS, GET_CATALOG, LIST_CATEGORIES, GET_PRODUCT));
        humanCaller();

        assertThat(rosterNames(filter(authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER), opa)))
                .containsExactly(LIST_CATALOGS, GET_CATALOG, LIST_CATEGORIES, GET_PRODUCT);
    }

    @Test // I29 — agent-gate OFF: the ceiling-only cut, asserted WIDER than the agent roster
    void theGateOffRosterIsTheCeilingOnlyCutAndIsWiderThanTheAgentRoster() throws IOException {
        // The ceiling DENIES get_product — so the gate-off roster is a real cut, not the whole
        // registry. (Deep review 2026-07-31: this stub used to allow all four names, which made the
        // containment assertion below hold by construction and unable to fail whatever the code did.)
        String opa = startPolicyStub(Set.of(LIST_CATALOGS, GET_CATALOG, LIST_CATEGORIES));
        agentCaller();
        AtomicReference<Boolean> sawAgentAttributes = new AtomicReference<>(Boolean.TRUE);

        properties.getAgentGate().setEnabled(false);
        ToolCallAuthorizer authorizer = new ToolCallAuthorizer(
                registry,
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                actorId -> {
                    sawAgentAttributes.set(Boolean.TRUE);
                    return new AgentCapabilityProfile(Set.of("READ"), CAPABLE_OF, Set.of("view"), "low");
                },
                CEILING_SUPPLIER,
                opaClient(opa, Duration.ofSeconds(2)),
                properties);
        sawAgentAttributes.set(Boolean.FALSE);

        List<String> gateOff = rosterNames(filter(authorizer, opa));

        assertThat(gateOff)
                .as("with the gate off the roster is the ceiling-only cut — the ceiling still bites")
                .containsExactly(LIST_CATALOGS, GET_CATALOG, LIST_CATEGORIES);
        assertThat(gateOff)
                .as("OFF must be WIDER than ON — a roster must never hide a callable tool")
                .containsAll(List.of(LIST_CATALOGS, GET_CATALOG));
        // With teeth: the ceiling refused get_product, so the roster must too. A gate-off roster that
        // returned the whole registry would pass the containment check above and fail here.
        assertThat(gateOff)
                .as("OFF removes the capability conjunct, NOT the ceiling")
                .doesNotContain(GET_PRODUCT);
        assertThat(sawAgentAttributes.get())
                .as("with the gate off the capability source is not even consulted")
                .isFalse();
    }

    // --- I27 / I31 : the contract guard and the by-name pairing ----------------------------------

    @Test // I27 — a contract-violating wrong-length vector lands on the EMPTY roster, never wider
    void aWrongLengthVectorFromASubstitutedClientYieldsTheEmptyRoster() throws IOException {
        String opa = startPolicyStub(CAPABLE_OF);
        agentCaller();
        // A stub OpaClient: the shipped HttpOpaClient normalises this away before a filter could see it,
        // but OpaClient is an implementable SPI, so the filter must not ASSUME totality.
        OpaClient shortVector = new StubOpaClient(List.of(Boolean.TRUE, Boolean.TRUE));

        RosterDecision decision = new ToolRosterFilter(
                registry, authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER), shortVector, properties)
                .decide();

        assertThat(decision.isUnfiltered())
                .as("a contract violation must land on the SMALLER result, not the unfiltered list")
                .isFalse();
        assertThat(namesOf(ToolRosterFilter.apply(decision, fullRoster()))).isEmpty();
    }

    @Test // I27 — an empty registry is an empty list with NO OPA call at all
    void anEmptyRegistryYieldsAnEmptyListWithoutCallingOpa() throws IOException {
        String opa = startPolicyStub(CAPABLE_OF);
        agentCaller();

        RosterDecision decision = new ToolRosterFilter(
                new ToolRegistry(List.of()),
                authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER),
                opaClient(opa, Duration.ofSeconds(2)),
                properties)
                .decide();

        assertThat(decision.isUnfiltered()).isFalse();
        assertThat(namesOf(ToolRosterFilter.apply(decision, fullRoster()))).isEmpty();
        assertThat(opaCalls.get()).isZero();
    }

    @Test // I31 — the decisions are applied BY NAME, so a divergent advertised order cannot shift them
    void aDivergentDelegateOrderStillYieldsTheSameCut() throws IOException {
        String opa = startPolicyStub(CAPABLE_OF);
        agentCaller();

        RosterDecision decision =
                filter(authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER), opa).decide();

        // The SDK advertises the same four tools in the OPPOSITE order to the registry's declaration
        // order. Zipping the boolean vector positionally would keep list_categories + get_product here.
        List<Tool> reversed = new ArrayList<>(fullRoster().tools());
        java.util.Collections.reverse(reversed);
        ListToolsResult served =
                ToolRosterFilter.apply(decision, new ListToolsResult(reversed, null, null));

        assertThat(namesOf(served)).containsExactlyInAnyOrder(LIST_CATALOGS, GET_CATALOG);
    }

    // --- I28 / I30 : the outside-the-batch degradation, and lossless rebuild ---------------------

    @Test // I28 — no AbacAuthentication at list time: the unfiltered list + the still-denying gate
    void anUnreadableIdentityDegradesToTheUnfilteredList() throws IOException {
        String opa = startPolicyStub(CAPABLE_OF);
        SecurityContextHolder.clearContext();
        ToolCallAuthorizer authorizer = authorizer(opa, CAPABILITY_SUPPLIER, CEILING_SUPPLIER);

        RosterDecision decision = filter(authorizer, opa).decide();

        assertThat(decision.isUnfiltered()).isTrue();
        assertThat(namesOf(ToolRosterFilter.apply(decision, fullRoster())))
                .containsExactly(LIST_CATALOGS, GET_CATALOG, LIST_CATEGORIES, GET_PRODUCT);
        assertThat(authorizer.authorize(GET_CATALOG).allowed())
                .as("the call-time gate denies on the very condition the roster degraded on")
                .isFalse();
    }

    @Test // I28 — a capability outage and an unresolvable ceiling degrade the same way
    void aCapabilityOutageOrAnUnresolvableCeilingDegradesToTheUnfilteredList() throws IOException {
        String opa = startPolicyStub(CAPABLE_OF);
        agentCaller();

        AgentCapabilitySupplier outage = actorId -> {
            throw new AgentCapabilityUnavailableException("the capability source is down", null);
        };
        assertThat(filter(authorizer(opa, outage, CEILING_SUPPLIER), opa).decide().isUnfiltered())
                .isTrue();

        newTurn();
        agentCaller();
        RoleDefinitionSupplier ceilingOutage = (userId, type, id) -> {
            throw new RoleResolutionException("the ceiling could not be resolved");
        };
        assertThat(filter(authorizer(opa, CAPABILITY_SUPPLIER, ceilingOutage), opa).decide().isUnfiltered())
                .isTrue();
    }

    @Test // I30 — nextCursor and meta survive the filter byte-identically
    void nextCursorAndMetaSurviveTheFilter() {
        Map<String, Object> meta = Map.of("progressToken", "t-42");
        ListToolsResult delegate = new ListToolsResult(fullRoster().tools(), "cursor-2", meta);

        ListToolsResult served =
                ToolRosterFilter.apply(RosterDecision.allowing(Set.of(LIST_CATALOGS)), delegate);

        assertThat(namesOf(served)).containsExactly(LIST_CATALOGS);
        assertThat(served.nextCursor()).isEqualTo("cursor-2");
        assertThat(served.meta()).isEqualTo(meta);
    }

    @Test // omit-never-fabricate: no failure mode may ever add a name the delegate did not advertise
    void noDecisionEverAddsAToolTheDelegateDidNotAdvertise() {
        ListToolsResult delegate = new ListToolsResult(
                List.of(tool(LIST_CATALOGS)), null, null);

        for (RosterDecision decision : List.of(
                RosterDecision.unfiltered(),
                RosterDecision.allowing(Set.of()),
                RosterDecision.allowing(Set.of(LIST_CATALOGS, "a_tool_that_does_not_exist")))) {
            assertThat(namesOf(ToolRosterFilter.apply(decision, delegate)))
                    .as("decision %s", decision)
                    .isSubsetOf(List.of(LIST_CATALOGS));
        }
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private ToolRosterFilter filter(ToolCallAuthorizer authorizer, String opaBaseUrl) {
        return new ToolRosterFilter(
                registry, authorizer, opaClient(opaBaseUrl, Duration.ofSeconds(2)), properties);
    }

    private List<String> rosterNames(ToolRosterFilter filter) {
        return namesOf(ToolRosterFilter.apply(filter.decide(), fullRoster()));
    }

    private ToolCallAuthorizer authorizer(
            String opaBaseUrl, AgentCapabilitySupplier capabilities, RoleDefinitionSupplier ceiling) {
        return new ToolCallAuthorizer(
                registry,
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                capabilities,
                ceiling,
                opaClient(opaBaseUrl, Duration.ofSeconds(2)),
                properties);
    }

    private OpaClient opaClient(String baseUrl, Duration timeout) {
        return new HttpOpaClient(
                MAPPER,
                new ToolPolicyPathResolver(properties),
                new OpaClientConfig(baseUrl, timeout, "allow"));
    }

    private ListToolsResult fullRosterInstance;

    private ListToolsResult fullRoster() {
        if (fullRosterInstance == null) {
            fullRosterInstance = new ListToolsResult(
                    DESCRIPTORS.stream().map(descriptor -> tool(descriptor.name())).toList(), null, null);
        }
        return fullRosterInstance;
    }

    private static Tool tool(String name) {
        return new Tool(name, null, name, Map.of("type", "object"), null, null, null, null);
    }

    private static List<String> namesOf(ListToolsResult result) {
        return result.tools().stream().map(Tool::name).toList();
    }

    private void agentCaller() {
        authenticate("user-alice", Map.of("act_chain", List.of("agent-readonly")));
    }

    private void humanCaller() {
        authenticate("user-alice", Map.of());
    }

    private static void authenticate(String subjectId, Map<String, Object> attributes) {
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(
                new AbacContext.Subject(subjectId, List.of("catalog-viewer"), attributes)));
    }

    /** End the current turn and start a new one — what makes a revocation visible (U16). */
    private static void newTurn() {
        RequestContextHolder.resetRequestAttributes();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private Thread callerThread(
            String subjectId, String opaBaseUrl, CountDownLatch bothReady,
            AtomicReference<List<String>> sink) {
        return new Thread(() -> {
            RequestContextHolder.setRequestAttributes(
                    new ServletRequestAttributes(new MockHttpServletRequest()));
            authenticate(subjectId, Map.of("act_chain", List.of("agent-" + subjectId)));
            try {
                bothReady.countDown();
                bothReady.await(5, TimeUnit.SECONDS);
                sink.set(rosterNames(
                        filter(authorizer(opaBaseUrl, CAPABILITY_SUPPLIER, CEILING_SUPPLIER),
                                opaBaseUrl)));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                RequestContextHolder.resetRequestAttributes();
                SecurityContextHolder.clearContext();
            }
        }, "roster-" + subjectId);
    }

    // --- the OPA stub -----------------------------------------------------------------------------

    private String startPolicyStub(Set<String> allowedTools) throws IOException {
        return startStub(exchange -> answerFromPolicy(exchange, allowedTools));
    }

    private String startStub(StubHandler handler) throws IOException {
        opaStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        opaStub.createContext("/", exchange -> {
            opaCalls.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        opaStub.start();
        return "http://127.0.0.1:" + opaStub.getAddress().getPort();
    }

    /** One rule, both endpoints — see the class javadoc for why that matters. */
    private static void answerFromPolicy(HttpExchange exchange, Set<String> allowedTools)
            throws IOException {
        answer(exchange, allowedTools::contains);
    }

    /** Allow depends on WHICH SUBJECT asked — so a cross-caller leak shows up as the wrong cut. */
    private static void answerPerSubject(HttpExchange exchange, Map<String, Set<String>> bySubject)
            throws IOException {
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        JsonNode input = body.path("input");
        JsonNode first = input.has("items") ? input.path("items").path(0) : input;
        String subject = first.path("subject").path("id").asString("");
        Set<String> allowed = bySubject.getOrDefault(subject, Set.of());
        answer(exchange, body, allowed::contains);
    }

    private static void answer(HttpExchange exchange, java.util.function.Predicate<String> allows)
            throws IOException {
        answer(exchange, MAPPER.readTree(exchange.getRequestBody()), allows);
    }

    private static void answer(
            HttpExchange exchange, JsonNode body, java.util.function.Predicate<String> allows)
            throws IOException {
        JsonNode input = body.path("input");
        if (input.has("items")) {
            List<String> decisions = new ArrayList<>();
            for (JsonNode item : input.path("items")) {
                decisions.add(String.valueOf(allows.test(item.path("resource").path("id").asString(""))));
            }
            respond(exchange, 200, "{\"result\":[" + String.join(",", decisions) + "]}");
            return;
        }
        boolean allowed = allows.test(input.path("resource").path("id").asString(""));
        respond(exchange, 200, "{\"result\":{\"allow\":" + allowed + "}}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /**
     * An {@code OpaClient} that violates the batch's length contract — something the shipped client
     * cannot do, and exactly why the filter must not assume it cannot happen (I27).
     */
    private record StubOpaClient(List<Boolean> decisions) implements OpaClient {

        @Override
        public boolean allow(AbacContext context) {
            return false;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.denyAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return decisions;
        }
    }
}
