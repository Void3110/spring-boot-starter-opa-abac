package dev.dmitriikonovalov.opaabac.mcp.authz;

import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityUnavailableException;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChain;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChainException;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The PEP: decides whether the caller may invoke a tool, <strong>before</strong> the tool body runs.
 *
 * <p>It builds the policy input and asks OPA. Everything that can go wrong on the way — an unreadable
 * identity, an unknown tool, an unavailable capability source, an unresolvable ceiling, an OPA outage —
 * lands on <strong>deny</strong>, and each lands with its own internal code so an operator can tell them
 * apart while the caller sees one uniform advisory.
 *
 * <h2>What is NOT here, on purpose</h2>
 * No target resource is resolved. The tool-gate is deliberately cheap and coarse: one OPA call, no
 * downstream lookups, so a tool the agent may not call never reaches a data path at all. The per-resource
 * question belongs to the service that owns the data, and it answers it independently when the tool body
 * calls it with the caller's own bearer.
 */
public class ToolCallAuthorizer {

    static final String CODE_POLICY_DENIED = "tool-gate-denied";
    static final String CODE_UNDECLARED_TOOL = "tool-undeclared";
    static final String CODE_IDENTITY_UNREADABLE = "tool-gate-identity-unreadable";
    static final String CODE_CAPABILITY_UNAVAILABLE = "tool-gate-capability-unavailable";
    static final String CODE_CEILING_UNAVAILABLE = "tool-gate-ceiling-unavailable";
    /**
     * The policy engine could not decide (ADR 0037): unreachable, timed out, not loaded. Distinct from
     * {@link #CODE_POLICY_DENIED} on purpose — an agent may retry this one later; retrying a deny is pointless.
     */
    static final String CODE_POLICY_UNAVAILABLE = "tool-gate-policy-unavailable";
    static final String CODE_UNAUTHENTICATED = "tool-gate-unauthenticated";

    private static final Logger log = LoggerFactory.getLogger(ToolCallAuthorizer.class);

    private final ToolRegistry registry;
    private final DelegationChainExtractor delegationChainExtractor;
    private final AgentCapabilitySupplier capabilitySupplier;
    private final PrincipalCeilingSupplier ceilingSupplier;
    private final OpaClient opaClient;
    private final ToolAuthorizationProperties properties;

    public ToolCallAuthorizer(
            ToolRegistry registry,
            DelegationChainExtractor delegationChainExtractor,
            AgentCapabilitySupplier capabilitySupplier,
            PrincipalCeilingSupplier ceilingSupplier,
            OpaClient opaClient,
            ToolAuthorizationProperties properties) {
        this.registry = registry;
        this.delegationChainExtractor = delegationChainExtractor;
        this.capabilitySupplier = capabilitySupplier;
        this.ceilingSupplier = ceilingSupplier;
        this.opaClient = opaClient;
        this.properties = properties;
    }

    /** Decide one tool call. Never throws for an authorization failure — it returns a denial. */
    public ToolAuthorizationDecision authorize(String toolName) {
        Optional<ToolDescriptor> declared = registry.find(toolName);
        if (declared.isEmpty()) {
            // An undeclared tool has no action, category or risk to authorize against. The registry
            // validator makes this unreachable for this server's own tools; it is still a deny, not a
            // gap, because "unclassifiable" must never mean "unrestricted".
            log.warn("Tool-gate denied: '{}' is not declared", toolName);
            return ToolAuthorizationDecision.denied(CODE_UNDECLARED_TOOL);
        }

        boolean applyAgentNarrowing = properties.getAgentGate().isEnabled();
        try {
            AbacContext context = buildContext(declared.get(), applyAgentNarrowing);
            if (context == null) {
                return ToolAuthorizationDecision.denied(CODE_UNAUTHENTICATED);
            }
            // OFF removes the agent NARROWING — not the gate. The context built above is an ordinary
            // human principal's, and it is still evaluated: the policy's human branch is exactly the
            // ceiling-only check, so the switch drops the capability conjunct and nothing else.
            //
            // It deliberately does NOT short-circuit to permitted() here. That would make the switch
            // disable call-time enforcement outright, which contradicts what this module promises in
            // three places (the guide's kill-switch table, ToolAuthorizationProperties, and the
            // configuration's javadoc) — and it would invert the roster's governing invariant, since
            // ToolRosterFilter still evaluates the same ceiling-only contexts and would then be
            // NARROWER than the call path, hiding tools the caller could successfully invoke. The
            // shipped OpaClient answers a policy deny with false and THROWS when it could not decide
            // (an outage, a timeout, a malformed body, a policy that is not loaded) — caught below.
            boolean allowed = opaClient.allow(context);
            if (!allowed) {
                log.debug("Tool-gate denied '{}' by policy", toolName);
                return ToolAuthorizationDecision.denied(CODE_POLICY_DENIED);
            }
            return ToolAuthorizationDecision.permitted();
        } catch (DelegationChainException e) {
            log.warn("Tool-gate denied '{}': the caller's identity could not be read ({})",
                    toolName, e.getMessage());
            return ToolAuthorizationDecision.denied(CODE_IDENTITY_UNREADABLE);
        } catch (AgentCapabilityUnavailableException e) {
            // The message only at WARN: a cause can be a parser exception quoting the source's body.
            log.warn("Tool-gate denied '{}': the agent capability source was unavailable ({})",
                    toolName, e.getMessage());
            log.debug("Capability source failure for '{}'", toolName, e);
            return ToolAuthorizationDecision.denied(CODE_CAPABILITY_UNAVAILABLE);
        } catch (CeilingUnavailableException e) {
            // Attributed by CALL SITE, not by type: whatever "could not decide" family member the
            // PrincipalCeilingSupplier threw — the core RoleResolutionException or an adopter's own
            // subtype — it is the ceiling that is unknown, not the policy engine.
            log.warn("Tool-gate denied '{}': the principal's ceiling could not be resolved ({})",
                    toolName, e.getMessage());
            log.debug("Ceiling resolution failure for '{}'", toolName, e.getCause());
            return ToolAuthorizationDecision.denied(CODE_CEILING_UNAVAILABLE);
        } catch (DecisionIndeterminateException e) {
            // Still a denial — the tool body never runs — but a distinct, caller-visible "not now".
            log.warn("Tool-gate denied '{}': the policy engine could not decide ({})", toolName, e.getMessage());
            return ToolAuthorizationDecision.denied(CODE_POLICY_UNAVAILABLE);
        }
    }

    /**
     * The policy input, exactly the shipped {@code AbacContext} shape used additively: the dual identity
     * rides in {@code subject.attributes}, the tool is the resource, and the principal's ceiling occupies
     * the same fields every other enforced path uses.
     *
     * <h2>Why {@code applyAgentNarrowing} is a parameter and not a field read</h2>
     * The roster ({@code ToolRosterFilter}) builds its contexts with this same method, and the roster's
     * governing invariant is that it must predict what the call path would decide <em>right now</em>.
     * With {@code agent-gate} OFF the call path evaluates an ordinary human principal — so if the roster
     * kept attaching the agent attributes it would keep narrowing by capability while calls did not, and
     * would hide tools the caller can successfully call. That is the one direction a hint must never fail
     * in ({@code 00-DESIGN} §3.2). Threading the switch through as an argument means both callers read it
     * in exactly one place and the two paths cannot drift apart.
     *
     * @param applyAgentNarrowing whether the agent identity and capability participate at all; when
     *                            false the context is an ordinary human principal's
     * @return null when there is no authenticated caller to build a context for
     */
    AbacContext buildContext(ToolDescriptor descriptor, boolean applyAgentNarrowing) {
        AbacContext.Subject caller = currentSubject();
        if (caller == null) {
            log.warn("Tool-gate denied '{}': no authenticated caller", descriptor.name());
            return null;
        }

        // Extracted even when narrowing is off: a MALFORMED claim must still deny (it is not a human),
        // and that distinction is the extractor's, not the switch's.
        DelegationChain chain = delegationChainExtractor.extract(caller);

        Map<String, Object> subjectAttributes = new LinkedHashMap<>(caller.attributes());
        if (applyAgentNarrowing && chain.isAgentCall()) {
            subjectAttributes.put("actor", chain.actor());
            subjectAttributes.put("chain", chain.chain());
            AgentCapabilityProfile capability = capabilitySupplier.lookup(chain.actor());
            subjectAttributes.put("agent_capability", capability);
        }

        // The ceiling: the principal's type-level authority for the type this tool reads — the union the
        // target service's own per-resource decisions sit inside, which is what makes the two layers agree.
        RoleDefinition roleDefinition = resolveCeiling(caller.id(), descriptor.targetType());

        return new AbacContext(
                new AbacContext.Subject(caller.id(), caller.roles(), subjectAttributes),
                descriptor.action(),
                new AbacContext.Resource(
                        "tool",
                        descriptor.name(),
                        Map.of(
                                "category", descriptor.category(),
                                "risk_tags", List.copyOf(descriptor.riskTags()),
                                "target_type", descriptor.targetType()),
                        List.of()),
                roleDefinition,
                Map.of());
    }

    /**
     * The principal's type-level ceiling, with an outage re-labelled by its call site: any
     * {@link DecisionIndeterminateException} from the ceiling source becomes a
     * {@link CeilingUnavailableException} (itself a family member, so a caller that only knows the family
     * — the roster — still reads it as "could not decide").
     */
    private RoleDefinition resolveCeiling(String subject, String resourceType) {
        try {
            return ceilingSupplier.lookup(subject, resourceType).orElse(null);
        } catch (DecisionIndeterminateException e) {
            throw new CeilingUnavailableException(e);
        }
    }

    /**
     * The ceiling could not be resolved — the {@link PrincipalCeilingSupplier}'s outage, labelled by where
     * it was thrown so the gate's code does not depend on which family member the supplier chose.
     */
    static final class CeilingUnavailableException extends DecisionIndeterminateException {

        private static final long serialVersionUID = 1L;

        CeilingUnavailableException(DecisionIndeterminateException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** The caller the starter's filter established, or null when nobody is authenticated. */
    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof AbacAuthentication abac ? abac.getSubject() : null;
    }
}
