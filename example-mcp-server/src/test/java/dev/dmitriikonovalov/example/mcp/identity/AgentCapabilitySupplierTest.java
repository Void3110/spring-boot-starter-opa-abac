package dev.dmitriikonovalov.example.mcp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * U13–U15: the tri-state contract at the source. The distinction that matters is
 * <em>authoritative-empty</em> (a real answer: this agent may do nothing) versus <em>outage</em> (we do
 * not know) — conflating them would either hide a broken dependency behind a plausible denial or, in
 * the other direction, turn a typo into an incident.
 */
class AgentCapabilitySupplierTest {

    private static AgentCapabilityProperties propertiesWith(
            AgentCapabilityProperties.Profile... profiles) {
        AgentCapabilityProperties properties = new AgentCapabilityProperties();
        properties.setProfiles(List.of(profiles));
        return properties;
    }

    private static AgentCapabilityProperties.Profile profile(String actorId) {
        AgentCapabilityProperties.Profile configured = new AgentCapabilityProperties.Profile();
        configured.setActorId(actorId);
        configured.setAllowedCategories(Set.of("READ"));
        configured.setAllowedTools(Set.of("list_catalogs", "get_catalog"));
        configured.setAllowedActions(Set.of("view", "list"));
        configured.setMaxRiskTag("low");
        return configured;
    }

    @Test // U13 — a resolved profile carries every dimension verbatim
    void resolvesAConfiguredActor() {
        AgentCapabilitySupplier supplier =
                new ConfigAgentCapabilitySupplier(propertiesWith(profile("agent-a")));

        AgentCapabilityProfile resolved = supplier.lookup("agent-a");

        assertThat(resolved.allowedCategories()).containsExactly("READ");
        assertThat(resolved.allowedTools()).containsExactlyInAnyOrder("list_catalogs", "get_catalog");
        assertThat(resolved.allowedActions()).containsExactlyInAnyOrder("view", "list");
        assertThat(resolved.maxRiskTag()).isEqualTo("low");
        assertThat(resolved.isEmpty()).isFalse();
    }

    @Test // U14 — an unknown actor is a real answer, not an error and not "unrestricted"
    void treatsAnUnknownActorAsAuthoritativeEmpty() {
        AgentCapabilitySupplier supplier =
                new ConfigAgentCapabilitySupplier(propertiesWith(profile("agent-a")));

        AgentCapabilityProfile resolved = supplier.lookup("agent-unknown");

        assertThat(resolved).isEqualTo(AgentCapabilityProfile.empty());
        assertThat(resolved.isEmpty()).isTrue();
        assertThat(resolved.allowedTools()).isEmpty();
    }

    @Test // U14 — never null; the absence of a profile has exactly one representation
    void neverReturnsNull() {
        AgentCapabilitySupplier supplier =
                new ConfigAgentCapabilitySupplier(new AgentCapabilityProperties());

        assertThat(supplier.lookup("anyone")).isNotNull();
        assertThat(supplier.lookup(null)).isEqualTo(AgentCapabilityProfile.empty());
    }

    @Test // U15 — a source failure is an OUTAGE, never a silent empty()
    void convertsASourceFailureIntoTheOutageSignal() {
        AgentCapabilityProperties failing = new AgentCapabilityProperties() {
            @Override
            public List<Profile> getProfiles() {
                throw new IllegalStateException("registry unreachable");
            }
        };
        AgentCapabilitySupplier supplier = new ConfigAgentCapabilitySupplier(failing);

        assertThatThrownBy(() -> supplier.lookup("agent-a"))
                .isInstanceOf(AgentCapabilityUnavailableException.class)
                .hasRootCauseMessage("registry unreachable");
    }

    @Test // U15 — a malformed profile is a source failure too, not a silently skipped entry
    void convertsAProfileWithoutAnActorIdIntoTheOutageSignal() {
        AgentCapabilityProperties.Profile anonymous = new AgentCapabilityProperties.Profile();
        anonymous.setAllowedTools(Set.of("list_catalogs"));

        AgentCapabilitySupplier supplier = new ConfigAgentCapabilitySupplier(propertiesWith(anonymous));

        assertThatThrownBy(() -> supplier.lookup("agent-a"))
                .isInstanceOf(AgentCapabilityUnavailableException.class);
    }

    @Test // the empty profile is a value, and it denies by having nothing in it
    void theEmptyProfileIsEmptyInEveryDimension() {
        AgentCapabilityProfile empty = AgentCapabilityProfile.empty();

        assertThat(empty.allowedCategories()).isEmpty();
        assertThat(empty.allowedTools()).isEmpty();
        assertThat(empty.allowedActions()).isEmpty();
        assertThat(empty.maxRiskTag()).isEmpty();
        assertThat(empty.isEmpty()).isTrue();
    }

    @Test // nulls collapse to empties rather than becoming NPEs at decision time
    void normalizesNullDimensions() {
        AgentCapabilityProfile profile = new AgentCapabilityProfile(null, null, null, null);

        assertThat(profile).isEqualTo(AgentCapabilityProfile.empty());
    }
}
