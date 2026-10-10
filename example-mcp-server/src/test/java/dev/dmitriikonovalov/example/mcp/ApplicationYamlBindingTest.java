package dev.dmitriikonovalov.example.mcp;

import static org.assertj.core.api.Assertions.assertThat;

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
 * {@code example.mcp.agents} homes. Nested YAML is invisible to a text grep for the dotted key, so this is
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
                .containsExactly("agent-readonly", "agent-overreach", "agent-revoked");
    }
}
