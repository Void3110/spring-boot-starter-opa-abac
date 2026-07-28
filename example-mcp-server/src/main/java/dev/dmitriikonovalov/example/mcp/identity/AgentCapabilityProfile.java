package dev.dmitriikonovalov.example.mcp.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

/**
 * What an agent is permitted to do — a set of <strong>narrowings</strong>, never a set of grants.
 *
 * <p>Nothing in here can widen anything. The tool-gate intersects these with the principal's ceiling
 * in Rego, so a profile naming a category or action the principal lacks contributes exactly nothing.
 * That is the whole agent model: capability restricts, the principal bounds, and the two meet in the
 * policy where the meeting is auditable.
 *
 * <p>Each dimension is an independent AND — satisfying one never compensates for failing another:
 * <ul>
 *   <li>{@code allowedCategories} — the permission categories the agent may act in;</li>
 *   <li>{@code allowedTools} — <strong>mandatory</strong>: a capability must name the tools it may
 *       call. An empty set therefore denies every tool rather than meaning "unrestricted", because the
 *       other reading would turn a profile someone trimmed to nothing into one that permits
 *       everything;</li>
 *   <li>{@code allowedActions} — the fine verbs, intersected with the principal's effective actions;</li>
 *   <li>{@code maxRiskTag} — the risk ceiling. The <em>ordering</em> deliberately lives in the policy,
 *       not here, so there is one place that decides and it is the auditable one.</li>
 * </ul>
 *
 * <p>The JSON names are the wire contract with {@code agent_tools.rego} and are pinned by
 * {@code AgentCapabilityProfileSerializationTest} — a rename on either side is a silent
 * "no capability", which the policy reads as deny, so it fails safe but would be baffling.
 */
public record AgentCapabilityProfile(
        @JsonProperty("allowed_categories") Set<String> allowedCategories,
        @JsonProperty("allowed_tools") Set<String> allowedTools,
        @JsonProperty("allowed_actions") Set<String> allowedActions,
        @JsonProperty("max_risk_tag") String maxRiskTag) {

    public AgentCapabilityProfile {
        allowedCategories = allowedCategories == null ? Set.of() : Set.copyOf(allowedCategories);
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
        maxRiskTag = maxRiskTag == null ? "" : maxRiskTag;
    }

    /**
     * The <strong>authoritative-empty</strong> profile: this agent is known and may do nothing.
     *
     * <p>A real answer, not a missing one — the distinction ADR 0014 exists for. Every dimension is
     * empty, so the policy denies every tool through the ordinary rules rather than through a special
     * case.
     */
    public static AgentCapabilityProfile empty() {
        return new AgentCapabilityProfile(Set.of(), Set.of(), Set.of(), "");
    }

    /**
     * True when this profile permits nothing at all.
     *
     * <p>{@link JsonIgnore} because this is a derived convenience, not part of the wire contract:
     * without it Jackson treats the accessor as a bean property and emits a stray {@code "empty"} key
     * into {@code input.subject.attributes.agent_capability}. The policy would ignore it, but a policy
     * input should contain exactly what the policy reads and nothing else.
     */
    @JsonIgnore
    public boolean isEmpty() {
        return allowedCategories.isEmpty() && allowedTools.isEmpty() && allowedActions.isEmpty();
    }
}
