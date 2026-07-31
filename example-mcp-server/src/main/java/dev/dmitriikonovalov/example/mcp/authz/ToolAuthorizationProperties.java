package dev.dmitriikonovalov.example.mcp.authz;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tool-gate settings, under {@code example.mcp.authz}.
 *
 * <h2>What OFF means, and why it is never wider than ON</h2>
 * {@code agent-gate.enabled=false} skips the <strong>tool-gate</strong>. The call is then evaluated
 * exactly as an ordinary human principal's would be — and the catalog service still enforces that
 * principal's ceiling on every resource it touches, because this server asserts nothing downstream. So
 * switching the agent gate off removes the <em>narrowing</em> and <strong>cannot grant an agent more
 * than its principal already has</strong>. That property is a consequence of enforcing the intersection
 * across two independent layers rather than propagating it, and it is what makes the kill-switch safe
 * to reach for during an incident.
 *
 * <p>There is deliberately <strong>no</strong> switch that disables call-time enforcement itself.
 */
@ConfigurationProperties("example.mcp.authz")
public class ToolAuthorizationProperties {

    private final AgentGate agentGate = new AgentGate();

    private final RosterFilter rosterFilter = new RosterFilter();

    private final RoleSource roleSource = new RoleSource();

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

    public RosterFilter getRosterFilter() {
        return rosterFilter;
    }

    public RoleSource getRoleSource() {
        return roleSource;
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

    /**
     * The {@code tools/list} roster-filter kill-switch.
     *
     * <h2>OFF is the post-upgrade escape hatch, and it is never wider than ON</h2>
     * The roster filter is installed by reaching two <em>pinned SDK internals</em> reflectively
     * ({@code RosterFilterInstaller}), and its smoke check deliberately <strong>fails startup</strong>
     * when an upgrade moves them. That is the right default — a demo whose flagship feature can quietly
     * vanish is worse than one that refuses to boot — but it would also make an SDK bump un-runnable
     * until the adapter is ported. Setting this to {@code false} skips the installation <em>and its
     * smoke check</em> entirely, so the server boots on the new SDK serving the unfiltered list.
     *
     * <p>That is safe because the roster is a <strong>hint</strong>: with the filter off, the served
     * list is exactly what the outside-the-batch degradation path already serves, and
     * <strong>call-time enforcement is untouched</strong> — every listed tool is still gated. There is
     * no property anywhere that disables the authoritative gate.
     */
    public static class RosterFilter {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Where the principal's role definition is resolved from (the user-management service). */
    public static class RoleSource {

        private String baseUrl = "http://localhost:8081";

        private Duration timeout = Duration.ofSeconds(2);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
