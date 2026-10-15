package dev.dmitriikonovalov.opaabac.mcp.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tool-gate settings, under {@code opa.abac.mcp}.
 *
 * <h2>What OFF means, and why it is never wider than ON</h2>
 * {@code agent-gate.enabled=false} drops the <strong>agent conjunct</strong> from the tool-gate — not
 * the gate itself. The policy is still asked, with a context that is an ordinary human principal's, so
 * the principal's own ceiling is still enforced at call time — and the target service enforces that
 * ceiling again on every resource it touches, because the gate asserts nothing downstream. So switching
 * the agent gate off removes the <em>narrowing</em> and <strong>cannot grant an agent more than its
 * principal already has</strong>. That property is a consequence of enforcing the intersection across two
 * independent layers rather than propagating it, and it is what makes the kill-switch safe to reach for
 * during an incident.
 *
 * <p>There is deliberately <strong>no</strong> switch that disables call-time enforcement itself.
 */
@ConfigurationProperties("opa.abac.mcp")
public class ToolAuthorizationProperties {

    private final AgentGate agentGate = new AgentGate();

    /**
     * The OPA document the tool-gate asks.
     *
     * <p>The <strong>package</strong> path, not a path to the rule: the shipped client POSTs to
     * {@code /v1/data/<policy-path>} and reads {@code result.<decision-field>} for a single decision, and
     * to {@code /v1/data/<policy-path>/bulk} for a batch. Pointing this at {@code agent_tools/allow}
     * would make the batch call resolve {@code /v1/data/agent_tools/allow/bulk}, which does not exist.
     */
    private String policyPath = "agent_tools";

    public AgentGate getAgentGate() {
        return agentGate;
    }

    public String getPolicyPath() {
        return policyPath;
    }

    public void setPolicyPath(String policyPath) {
        this.policyPath = policyPath;
    }

    /** The agent-narrowing kill-switch. */
    public static class AgentGate {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
