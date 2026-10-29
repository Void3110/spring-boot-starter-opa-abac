package dev.dmitriikonovalov.example.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.mcp.authz.ExampleAuthzProperties;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolAuthorizationProperties;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProperties;
import dev.dmitriikonovalov.opaabac.mcp.identity.IdentityProperties;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * U4 — the example's own {@code application.yml} carries the tool-gate's settings under the module's
 * {@code opa.abac.mcp.*} prefixes, and nothing under the retired {@code example.mcp.identity} /
 * {@code example.mcp.agents} / {@code example.mcp.authz.{agent-gate,policy-path}} homes (U4, U38). Nested YAML is invisible to a text grep for the dotted key, so this is
 * the check that sees it.
 */
class ApplicationYamlBindingTest {

    private static Binder binder;

    @BeforeAll
    static void loadTheShippedYaml() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        binder = new Binder(ConfigurationPropertySources.from(sources));
    }

    @Test
    void nothingBindsUnderTheRetiredPrefixes() {
        assertThat(binder.bind("example.mcp.identity", Bindable.mapOf(String.class, Object.class)).isBound())
                .isFalse();
        assertThat(binder.bind("example.mcp.agents", Bindable.mapOf(String.class, Object.class)).isBound())
                .isFalse();
    }

    @Test
    void theModulePrefixesBind() {
        IdentityProperties identity =
                binder.bind("opa.abac.mcp.identity", Bindable.of(IdentityProperties.class)).get();
        assertThat(identity.getActorClaim()).isEqualTo("act_chain");

        AgentCapabilityProperties agents =
                binder.bind("opa.abac.mcp.agents", Bindable.of(AgentCapabilityProperties.class)).get();
        assertThat(agents.getProfiles()).extracting(AgentCapabilityProperties.Profile::getActorId)
                .containsExactly("agent-readonly", "agent-overreach", "agent-revoked", "agent-host");
    }

    @Test // U7 — the MCP host is an agent: its profile, its client listed, and azp copied for the guard
    void theHostAgentIsWiredFromTheShippedYaml() {
        AgentCapabilityProperties.Profile host =
                binder.bind("opa.abac.mcp.agents", Bindable.of(AgentCapabilityProperties.class)).get()
                        .getProfiles().stream()
                        .filter(profile -> "agent-host".equals(profile.getActorId()))
                        .findFirst().orElseThrow();
        assertThat(host.getAllowedCategories()).containsExactly("READ");
        assertThat(host.getAllowedTools())
                .containsExactly("list_catalogs", "get_catalog", "list_categories", "get_product");
        assertThat(host.getAllowedActions()).containsExactly("view", "list");
        assertThat(host.getMaxRiskTag()).isEqualTo("low");

        IdentityProperties identity =
                binder.bind("opa.abac.mcp.identity", Bindable.of(IdentityProperties.class)).get();
        assertThat(identity.getAgentClients()).containsExactly(
                "catalog-agent-readonly", "catalog-agent-overreach", "catalog-agent-revoked", "catalog-mcp-host");
        assertThat(binder.bind("opa.abac.subject.attribute-claims", Bindable.listOf(String.class)).get())
                .containsExactly("act_chain", "azp");
    }

    @Test // U38 — the authz rename is complete, and the example-owned keys stay where they were
    void theGateSettingsBindUnderTheModuleAndTheExampleKeepsItsOwn() {
        assertThat(binder.bind("example.mcp.authz.agent-gate", Bindable.mapOf(String.class, Object.class))
                .isBound()).isFalse();
        assertThat(binder.bind("example.mcp.authz.policy-path", String.class).isBound()).isFalse();

        assertThat(binder.bind("opa.abac.mcp.agent-gate.enabled", Boolean.class).isBound()).isTrue();
        assertThat(binder.bind("opa.abac.mcp.policy-path", String.class).get()).isEqualTo("agent_tools");
        ToolAuthorizationProperties gate =
                binder.bind("opa.abac.mcp", Bindable.of(ToolAuthorizationProperties.class)).get();
        assertThat(gate.getAgentGate().isEnabled()).isTrue();

        ExampleAuthzProperties example =
                binder.bind("example.mcp.authz", Bindable.of(ExampleAuthzProperties.class)).get();
        assertThat(binder.bind("example.mcp.authz.role-source.base-url", String.class).isBound()).isTrue();
        assertThat(binder.bind("example.mcp.authz.roster-filter.enabled", Boolean.class).isBound()).isTrue();
        assertThat(example.getRoleSource().getBaseUrl()).isEqualTo("http://localhost:8081");
        assertThat(example.getRosterFilter().isEnabled()).isTrue();
    }
}
