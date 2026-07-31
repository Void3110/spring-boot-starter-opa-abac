package dev.dmitriikonovalov.example.mcp.authz;

import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilityUnavailableException;
import dev.dmitriikonovalov.example.mcp.identity.DelegationChainException;
import dev.dmitriikonovalov.example.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.example.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The roster pre-flight: decides which of the declared tools a caller may see, so a {@code tools/list}
 * advertises only what that caller can actually call.
 *
 * <p>This is the <strong>durable core</strong> of the roster mechanism — it owns every semantic and every
 * test, and it survives the SDK upgrade that will delete {@link RosterFilterInstaller}. Nothing here knows
 * how the filter is reached; it takes the caller's ambient identity and a delegate's result and answers.
 *
 * <h2>The list is a hint, never a grant</h2>
 * Omitting a tool from the roster does not disable it: {@code ToolCallGate} still runs on every
 * {@code tools/call} and is the authoritative decision. That is deliberate — it is what catches a
 * mid-session revocation, and it is why degrading this filter is safe. The corollary is a rule with no
 * exceptions: <strong>nothing may ever treat a roster answer as a grant</strong>. No roster-derived cache,
 * no "already checked in the pre-flight" shortcut. The moment one appears, the hint has silently become an
 * authorization.
 *
 * <h2>Two phases, and why the split is not cosmetic</h2>
 * {@link #decide()} does all the work that depends on the calling thread — reading
 * {@code SecurityContextHolder}, the turn-scoped capability memo riding the request attributes, the
 * ceiling lookup, the OPA batch — and must run on the request thread. {@link #apply} is pure and may run
 * anywhere. The SDK's list handler returns a {@code Mono}, and the lambda passed to {@code map} runs at
 * subscription time; assuming that is the same thread would make the whole feature depend on an
 * unwritten scheduling detail. Deciding eagerly and applying lazily removes the assumption: the
 * thread-locals are read while they are provably in scope.
 *
 * <h2>Failure semantics — three classes, deliberately different</h2>
 * <ul>
 *   <li><strong>The batch cannot report failure.</strong> {@link OpaClient#allowAll} is contractually
 *       total and fail-closed: it never throws and normalises outage, timeout, non-200, malformed body
 *       and length mismatch alike into an all-{@code false} vector. So an all-{@code false} answer is
 *       taken as <strong>authoritative — an empty roster</strong>, whether it came from a dead PDP or a
 *       zero-capability agent. That is honest in both cases: during that outage every {@code tools/call}
 *       denies too, so a roster advertising four unusable tools would be the misleading answer.</li>
 *   <li><strong>The edges outside the batch degrade</strong> to the unfiltered list plus a WARN — an
 *       unreadable identity, a capability outage, an unresolvable ceiling. These can genuinely fail, and
 *       the hint carries no authority, so showing more is safe while the gate keeps denying.</li>
 *   <li><strong>A wrong-length vector lands on the empty roster.</strong> The shipped client cannot
 *       produce one, but {@link OpaClient} is an adopter-implementable SPI that deliberately refuses a
 *       default implementation precisely so nobody inherits a fail-open filter — so this class does not
 *       <em>assume</em> totality. Landing on empty rather than unfiltered keeps the rule "no edge widens
 *       the result" true even when the contract below is violated.</li>
 * </ul>
 *
 * <p>And in every mode: <strong>never fabricate</strong>. A tool is only ever omitted, never invented.
 */
public class ToolRosterFilter {

    private static final Logger log = LoggerFactory.getLogger(ToolRosterFilter.class);

    private final ToolRegistry registry;
    private final ToolCallAuthorizer authorizer;
    private final OpaClient opaClient;
    private final ToolAuthorizationProperties properties;

    public ToolRosterFilter(
            ToolRegistry registry,
            ToolCallAuthorizer authorizer,
            OpaClient opaClient,
            ToolAuthorizationProperties properties) {
        this.registry = registry;
        this.authorizer = authorizer;
        this.opaClient = opaClient;
        this.properties = properties;
    }

    /**
     * Ask the policy which tools this caller may see. <strong>Must run on the request thread</strong> —
     * it reads the security context and the turn-scoped memo.
     *
     * <p>Uniform for every caller: a human (no delegation chain) is evaluated ceiling-only and an agent
     * gets ceiling ∩ capability, both by the same {@code bulk} rule. The contrast comes from the policy,
     * not from a branch here.
     */
    public RosterDecision decide() {
        List<ToolDescriptor> declared = registry.all();
        if (declared.isEmpty()) {
            // No tools to ask about. Answering "nothing is allowed" without a round-trip is the same
            // answer OPA would give, for free.
            return RosterDecision.allowing(Set.of());
        }

        // The switch is read ONCE, here, and threaded into every context: with the agent gate off the
        // call path evaluates an ordinary human principal, so the roster must too — otherwise it would
        // hide tools the caller can successfully call.
        boolean applyAgentNarrowing = properties.getAgentGate().isEnabled();

        List<AbacContext> contexts = new ArrayList<>(declared.size());
        try {
            for (ToolDescriptor descriptor : declared) {
                AbacContext context = authorizer.buildContext(descriptor, applyAgentNarrowing);
                if (context == null) {
                    // No authenticated caller at list time. The chain rejects an anonymous caller before
                    // it reaches this surface, and the call-time gate denies on exactly this condition,
                    // so the hint may safely show everything.
                    log.warn("Roster unfiltered: no authenticated caller at list time");
                    return RosterDecision.unfiltered();
                }
                contexts.add(context);
            }
        } catch (DelegationChainException e) {
            log.warn("Roster unfiltered: the caller's identity could not be read ({}). "
                    + "The call-time gate still denies every tool for the same reason.", e.getMessage());
            return RosterDecision.unfiltered();
        } catch (AgentCapabilityUnavailableException e) {
            log.warn("Roster unfiltered: the agent capability source was unavailable. "
                    + "The call-time gate still denies every tool for the same outage.", e);
            return RosterDecision.unfiltered();
        } catch (RoleResolutionException e) {
            log.warn("Roster unfiltered: the principal's ceiling could not be resolved. "
                    + "The authoritative deny still happens per call.", e);
            return RosterDecision.unfiltered();
        }

        // ONE round-trip for the whole roster — the batch primitive, not N single calls.
        List<Boolean> decisions = opaClient.allowAll(contexts);

        if (decisions == null || decisions.size() != contexts.size()) {
            // Unreachable via HttpOpaClient (it normalises this to all-false), but OpaClient is an
            // implementable SPI. Land on the SMALLER result: a contract violation must not widen.
            log.warn("Roster empty: the OpaClient returned {} decision(s) for {} context(s) — "
                            + "a contract violation, failing closed rather than serving an unfiltered "
                            + "or index-shifted roster",
                    decisions == null ? "null" : decisions.size(), contexts.size());
            return RosterDecision.allowing(Set.of());
        }

        Set<String> allowed = new LinkedHashSet<>();
        for (int i = 0; i < decisions.size(); i++) {
            if (Boolean.TRUE.equals(decisions.get(i))) {
                allowed.add(declared.get(i).name());
            }
        }
        log.debug("Roster resolved: {} of {} declared tool(s) allowed", allowed.size(), declared.size());
        return RosterDecision.allowing(allowed);
    }

    /**
     * Apply a decision to the delegate's result. Pure — safe on any thread.
     *
     * <h2>By name, never by index</h2>
     * {@link #decide()} pairs booleans with {@link ToolRegistry} declaration order, but the list being
     * filtered here is the SDK's, whose order is produced by Spring AI's annotation scanner and is
     * nowhere contracted to match. Zipping the vector positionally onto this list would advertise one
     * tool on another tool's {@code true} the day the two orders diverge — a widening bug that a fixture
     * whose orders happen to agree would never catch. Matching by name makes the index shift
     * structurally impossible rather than merely untested.
     *
     * <h2>Rebuild with the full constructor</h2>
     * {@code ListToolsResult} is a record carrying {@code nextCursor} and {@code meta} as well as the
     * tools, and {@code builder(kept).build()} silently drops both. Omission-only means omitting
     * <em>tools</em> — never losing response fields.
     */
    public static ListToolsResult apply(RosterDecision decision, ListToolsResult delegate) {
        if (delegate == null || decision.isUnfiltered()) {
            return delegate;
        }
        List<Tool> tools = delegate.tools();
        if (tools == null) {
            return delegate;
        }
        List<Tool> kept = tools.stream().filter(tool -> decision.permits(tool.name())).toList();
        if (kept.size() == tools.size()) {
            return delegate;
        }
        return new ListToolsResult(kept, delegate.nextCursor(), delegate.meta());
    }
}
