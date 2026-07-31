package dev.dmitriikonovalov.example.mcp.authz;

import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.example.mcp.identity.AgentCapabilityUnavailableException;
import dev.dmitriikonovalov.example.mcp.identity.DelegationChain;
import dev.dmitriikonovalov.example.mcp.identity.DelegationChainException;
import dev.dmitriikonovalov.example.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.example.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.example.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
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
    static final String CODE_UNAUTHENTICATED = "tool-gate-unauthenticated";

    private static final Logger log = LoggerFactory.getLogger(ToolCallAuthorizer.class);

    private final ToolRegistry registry;
    private final DelegationChainExtractor delegationChainExtractor;
    private final AgentCapabilitySupplier capabilitySupplier;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final OpaClient opaClient;
    private final ToolAuthorizationProperties properties;

    public ToolCallAuthorizer(
            ToolRegistry registry,
            DelegationChainExtractor delegationChainExtractor,
            AgentCapabilitySupplier capabilitySupplier,
            RoleDefinitionSupplier roleDefinitionSupplier,
            OpaClient opaClient,
            ToolAuthorizationProperties properties) {
        this.registry = registry;
        this.delegationChainExtractor = delegationChainExtractor;
        this.capabilitySupplier = capabilitySupplier;
        this.roleDefinitionSupplier = roleDefinitionSupplier;
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
            if (!applyAgentNarrowing) {
                // OFF removes the NARROWING only. The catalog service still enforces the principal's
                // ceiling on every resource, so this cannot grant beyond the principal.
                log.debug("Tool-gate skipped for '{}' (agent-gate disabled)", toolName);
                return ToolAuthorizationDecision.permitted();
            }
            // The shipped OpaClient is fail-closed by contract: any outage, timeout, malformed body or
            // missing decision field returns false rather than throwing.
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
            log.warn("Tool-gate denied '{}': the agent capability source was unavailable", toolName, e);
            return ToolAuthorizationDecision.denied(CODE_CAPABILITY_UNAVAILABLE);
        } catch (RoleResolutionException e) {
            log.warn("Tool-gate denied '{}': the principal's role could not be resolved", toolName, e);
            return ToolAuthorizationDecision.denied(CODE_CEILING_UNAVAILABLE);
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

        // The ceiling: resolved for the type this tool reads, by the same supplier and the same
        // coordinates the catalog service would use — which is what makes the two layers agree.
        RoleDefinition roleDefinition = roleDefinitionSupplier
                .lookup(caller.id(), descriptor.targetType(), null)
                .orElse(null);

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

    /** The caller the starter's filter established, or null when nobody is authenticated. */
    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof AbacAuthentication abac ? abac.getSubject() : null;
    }
}
