package dev.dmitriikonovalov.example.mcp.identity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The demo agent registry, under {@code example.mcp.agents}.
 *
 * <p>A <strong>list</strong> of profiles each naming its actor, rather than a map keyed by actor id:
 * Spring's relaxed binding rewrites map keys (an id like {@code agent-readonly} would need quoting to
 * survive intact), and an actor id is an identity that must round-trip byte-for-byte. A list keeps the
 * id an ordinary value.
 *
 * <p>Config is the demo's source. A real deployment replaces {@link ConfigAgentCapabilitySupplier}
 * with one backed by an agent registry; the tri-state contract is what makes that a one-bean change.
 */
@ConfigurationProperties("example.mcp.agents")
public class AgentCapabilityProperties {

    private List<Profile> profiles = new ArrayList<>();

    public List<Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(List<Profile> profiles) {
        this.profiles = profiles == null ? new ArrayList<>() : profiles;
    }

    /** One agent's capability, as configured. */
    public static class Profile {

        private String actorId;

        private Set<String> allowedCategories = new LinkedHashSet<>();

        /** Mandatory: an empty list denies every tool. See {@link AgentCapabilityProfile}. */
        private Set<String> allowedTools = new LinkedHashSet<>();

        private Set<String> allowedActions = new LinkedHashSet<>();

        private String maxRiskTag = "";

        public String getActorId() {
            return actorId;
        }

        public void setActorId(String actorId) {
            this.actorId = actorId;
        }

        public Set<String> getAllowedCategories() {
            return allowedCategories;
        }

        public void setAllowedCategories(Set<String> allowedCategories) {
            this.allowedCategories = allowedCategories;
        }

        public Set<String> getAllowedTools() {
            return allowedTools;
        }

        public void setAllowedTools(Set<String> allowedTools) {
            this.allowedTools = allowedTools;
        }

        public Set<String> getAllowedActions() {
            return allowedActions;
        }

        public void setAllowedActions(Set<String> allowedActions) {
            this.allowedActions = allowedActions;
        }

        public String getMaxRiskTag() {
            return maxRiskTag;
        }

        public void setMaxRiskTag(String maxRiskTag) {
            this.maxRiskTag = maxRiskTag;
        }

        AgentCapabilityProfile toProfile() {
            return new AgentCapabilityProfile(
                    allowedCategories, allowedTools, allowedActions, maxRiskTag);
        }
    }
}
