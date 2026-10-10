package dev.dmitriikonovalov.opaabac.mcp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * U4 — the module's properties bind under {@code opa.abac.mcp.*}, and the example's retired prefixes do
 * not: a deployment still carrying {@code example.mcp.identity} / {@code example.mcp.agents} keeps the
 * defaults (the conventional claim, no profiles — every agent authoritative-empty), never a half-read
 * configuration.
 */
class McpPropertiesBindingTest {

    private static <T> T bind(Class<T> type, Map<String, String> values) {
        String prefix = type.getAnnotation(ConfigurationProperties.class).value();
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bind(prefix, Bindable.of(type)).orElseGet(() -> newInstance(type));
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theNewPrefixesBind() {
        Map<String, String> values = Map.of(
                "opa.abac.mcp.identity.actor-claim", "x",
                "opa.abac.mcp.agents.profiles[0].actor-id", "a",
                "opa.abac.mcp.agents.profiles[0].allowed-tools", "list_catalogs");

        assertThat(bind(IdentityProperties.class, values).getActorClaim()).isEqualTo("x");
        AgentCapabilityProperties agents = bind(AgentCapabilityProperties.class, values);
        assertThat(agents.getProfiles()).hasSize(1);
        assertThat(agents.getProfiles().get(0).getActorId()).isEqualTo("a");
        assertThat(agents.getProfiles().get(0).getAllowedTools()).containsExactly("list_catalogs");
    }

    @Test
    void theRetiredExamplePrefixesNoLongerBind() {
        Map<String, String> values = Map.of(
                "example.mcp.identity.actor-claim", "x",
                "example.mcp.agents.profiles[0].actor-id", "a");

        assertThat(bind(IdentityProperties.class, values).getActorClaim()).isEqualTo("act_chain");
        assertThat(bind(AgentCapabilityProperties.class, values).getProfiles()).isEmpty();
    }

    @Test // ADR 0036 §13: the list shape keeps an actor id with dots intact — a map key would not
    void anActorIdWithDotsRoundTripsThroughTheListShape() {
        Map<String, String> values = Map.of("opa.abac.mcp.agents.profiles[0].actor-id", "svc.agent-1");

        assertThat(bind(AgentCapabilityProperties.class, values).getProfiles().get(0).getActorId())
                .isEqualTo("svc.agent-1");
    }
}
