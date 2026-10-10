package dev.dmitriikonovalov.opaabac.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.mcp.identity.ActorRequiredException;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.opaabac.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.IdentityProperties;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolErrorLayer;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code agent-clients} guard (MCP-AUTH-LIBRARY T6, ADR 0036 §8): a token from an OAuth client listed as
 * an agent, but carrying no actor claim, is a misconfigured agent client — denied
 * {@code tool-gate-actor-required}, never evaluated as a human call (U26, U27, U29).
 *
 * <p>Every reader of the switch is covered: the call path (U27), the roster (U29) — both through the one
 * {@code buildContext} — and the agent-gate OFF case for each, since the guard is an identity check and the
 * agent-gate switch must not turn it off. The startup wiring check is {@code ActorClaimWiringCheckTest}'s.
 */
class AgentClientsGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL = "get_catalog";
    private static final ToolDescriptor DESCRIPTOR =
            new ToolDescriptor(TOOL, "view", "READ", "catalog", Set.of("low"));
    private static final RoleDefinition CEILING = new RoleDefinition(
            "type-level:catalog", Map.of(), Map.of("catalog", List.of("READ")), Map.of(), Map.of(), null);
    private static final AgentCapabilitySupplier CAPABILITY =
            actorId -> new AgentCapabilityProfile(Set.of("READ"), Set.of(TOOL), Set.of("view"), "low");

    private final ToolAuthorizationProperties properties = new ToolAuthorizationProperties();
    private final RecordingOpaClient opa = new RecordingOpaClient();
    private ListAppender<ILoggingEvent> warnings;

    @BeforeEach
    void captureWarnings() {
        warnings = new ListAppender<>();
        warnings.start();
        for (Class<?> source : List.of(ToolCallAuthorizer.class, ToolRosterFilter.class)) {
            ((Logger) LoggerFactory.getLogger(source)).addAppender(warnings);
        }
    }

    @AfterEach
    void tearDown() {
        for (Class<?> source : List.of(ToolCallAuthorizer.class, ToolRosterFilter.class)) {
            ((Logger) LoggerFactory.getLogger(source)).detachAppender(warnings);
        }
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Map<String, Object> attributes) {
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(
                new AbacContext.Subject("user-alice", List.of("catalog-viewer"), attributes)));
    }

    private ToolCallAuthorizer authorizer(List<String> agentClients) {
        return new ToolCallAuthorizer(
                new ToolRegistry(List.of(DESCRIPTOR)),
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                CAPABILITY,
                (subject, type) -> Optional.of(CEILING),
                opa,
                properties,
                agentClients);
    }

    private List<String> warnLines() {
        return warnings.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // --- U26 : an empty list changes nothing ------------------------------------------------------

    @Test // U26 — empty list: the no-actor caller's context is byte-identical to the pre-guard constructor's
    void anEmptyListBuildsExactlyTheContextTheGuardlessAuthorizerBuilds() {
        authenticate(Map.of("azp", "catalog-gateway"));
        ToolCallAuthorizer guardless = new ToolCallAuthorizer(
                new ToolRegistry(List.of(DESCRIPTOR)),
                new ClaimDelegationChainExtractor(MAPPER, new IdentityProperties()),
                CAPABILITY,
                (subject, type) -> Optional.of(CEILING),
                opa,
                properties);

        AbacContext before = guardless.buildContext(DESCRIPTOR, true);
        AbacContext after = authorizer(List.of()).buildContext(DESCRIPTOR, true);

        assertThat(after).isEqualTo(before);
        assertThat(after.subject().attributes())
                .as("the human path: azp rides along, no agent attribute, no new key")
                .containsOnlyKeys("azp");
    }

    @Test // U27 — azp NOT listed + no actor ⇒ the human path, not a deny
    void anUnlistedClientWithoutAnActorIsAHumanCall() {
        authenticate(Map.of("azp", "catalog-gateway"));

        ToolAuthorizationDecision decision = authorizer(List.of("some-agent-client")).authorize(TOOL);

        assertThat(decision.allowed()).isTrue();
        assertThat(opa.inputs).singleElement()
                .satisfies(input -> assertThat(input.subject().attributes()).doesNotContainKey("actor"));
    }

    // --- U27 : the guard ---------------------------------------------------------------------------

    @Test // U27 — azp listed + no actor ⇒ tool-gate-actor-required at layer tool-gate, a WARN naming the client
    void aListedClientWithoutAnActorIsDeniedActorRequired() {
        authenticate(Map.of("azp", "catalog-gateway"));

        ToolAuthorizationDecision decision = authorizer(List.of("catalog-gateway")).authorize(TOOL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.layer()).isEqualTo(ToolErrorLayer.TOOL_GATE);
        assertThat(decision.code())
                .isEqualTo("tool-gate-actor-required")
                .isEqualTo(ToolCallAuthorizer.CODE_ACTOR_REQUIRED);
        assertThat(opa.inputs).as("a deterministic deny spends no policy call").isEmpty();
        assertThat(warnLines()).anySatisfy(line -> assertThat(line)
                .contains("catalog-gateway").contains("agent-clients").contains("no actor claim"));
    }

    @Test // U27 — the same with the agent gate OFF: an identity check, not an agent-narrowing conjunct
    void theGuardStillDeniesWithTheAgentGateOff() {
        properties.getAgentGate().setEnabled(false);
        authenticate(Map.of("azp", "catalog-gateway"));

        ToolAuthorizationDecision decision = authorizer(List.of("catalog-gateway")).authorize(TOOL);

        assertThat(decision.code()).isEqualTo(ToolCallAuthorizer.CODE_ACTOR_REQUIRED);
        assertThat(opa.inputs).isEmpty();
    }

    @Test // U27 — azp listed + a VALID actor ⇒ the agent path (actor + capability reach the policy)
    void aListedClientWithAValidActorTakesTheAgentPath() {
        authenticate(Map.of("azp", "catalog-agent-readonly", "act_chain", List.of("agent-readonly")));

        ToolAuthorizationDecision decision = authorizer(List.of("catalog-agent-readonly")).authorize(TOOL);

        assertThat(decision.allowed()).isTrue();
        assertThat(opa.inputs).singleElement().satisfies(input -> assertThat(input.subject().attributes())
                .containsEntry("actor", "agent-readonly")
                .containsKey("agent_capability"));
    }

    @Test // U27 — azp listed + a MALFORMED actor ⇒ identity-unreadable: the extractor's deny wins
    void aMalformedActorStaysIdentityUnreadableEvenForAListedClient() {
        authenticate(Map.of("azp", "catalog-gateway", "act_chain", "not-an-array"));

        ToolAuthorizationDecision decision = authorizer(List.of("catalog-gateway")).authorize(TOOL);

        assertThat(decision.code()).isEqualTo(ToolCallAuthorizer.CODE_IDENTITY_UNREADABLE);
    }

    @Test // a non-string azp is never "listed" — the guard compares client ids, nothing else
    void aNonStringAzpIsNotTreatedAsAListedClient() {
        authenticate(Map.of("azp", List.of("catalog-gateway")));

        assertThat(authorizer(List.of("catalog-gateway")).authorize(TOOL).allowed()).isTrue();
    }

    @Test // the exception names the client only — never token content
    void theExceptionCarriesTheClientIdOnly() {
        authenticate(Map.of("azp", "catalog-gateway", "secret_claim", "s3cr3t"));

        ToolCallAuthorizer authorizer = authorizer(List.of("catalog-gateway"));

        assertThatThrownBy(() -> authorizer.buildContext(DESCRIPTOR, true))
                .isInstanceOfSatisfying(ActorRequiredException.class,
                        e -> assertThat(e.clientId()).isEqualTo("catalog-gateway"))
                .hasMessageNotContaining("s3cr3t");
    }

    // --- U29 : the roster under the guard ----------------------------------------------------------

    @Test // U29 — a listed client without an actor at list time ⇒ unfiltered + WARN; every call still denies
    void theRosterIsUnfilteredWhileEveryCallDenies() {
        authenticate(Map.of("azp", "catalog-gateway"));
        ToolCallAuthorizer authorizer = authorizer(List.of("catalog-gateway"));
        ToolRosterFilter roster =
                new ToolRosterFilter(new ToolRegistry(List.of(DESCRIPTOR)), authorizer, opa, properties);

        RosterDecision decision = roster.decide();

        assertThat(decision.isUnfiltered()).isTrue();
        assertThat(opa.batches).as("no batch is spent on an identity edge").hasValue(0);
        assertThat(warnLines()).anySatisfy(line -> assertThat(line)
                .startsWith("Roster unfiltered").contains("catalog-gateway"));
        assertThat(authorizer.authorize(TOOL).code()).isEqualTo(ToolCallAuthorizer.CODE_ACTOR_REQUIRED);
    }

    @Test // U29 — the same with the agent gate OFF (every reader of the switch agrees)
    void theRosterDegradesTheSameWayWithTheAgentGateOff() {
        properties.getAgentGate().setEnabled(false);
        authenticate(Map.of("azp", "catalog-gateway"));
        ToolCallAuthorizer authorizer = authorizer(List.of("catalog-gateway"));
        ToolRosterFilter roster =
                new ToolRosterFilter(new ToolRegistry(List.of(DESCRIPTOR)), authorizer, opa, properties);

        assertThat(roster.decide().isUnfiltered()).isTrue();
        assertThat(authorizer.authorize(TOOL).code()).isEqualTo(ToolCallAuthorizer.CODE_ACTOR_REQUIRED);
    }

    /** Allows everything and records what it was asked — the guard's denials must never reach it. */
    private static final class RecordingOpaClient implements OpaClient {
        final List<AbacContext> inputs = new ArrayList<>();
        final AtomicInteger batches = new AtomicInteger();

        @Override
        public boolean allow(AbacContext context) {
            inputs.add(context);
            return true;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.denyAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            batches.incrementAndGet();
            return contexts.stream().map(c -> Boolean.TRUE).toList();
        }
    }
}
