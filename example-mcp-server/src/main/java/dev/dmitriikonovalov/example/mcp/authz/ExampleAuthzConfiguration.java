package dev.dmitriikonovalov.example.mcp.authz;

import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolRosterFilter;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * The example's own pieces of the tool-gate. Everything else — the PEP, the roster decision, the
 * identity extractor, the YAML capability source, the policy-path decorator, the memos and the gate on
 * every tool specification — is built by the starter's {@code OpaMcpAutoConfiguration} once the
 * {@code opa-abac-mcp} module sits next to Spring AI's MCP server. What is left here is what only this
 * application can supply: its ceiling source, and the reflective roster installer the published module
 * deliberately does not ship (ADR 0036 §5).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExampleAuthzProperties.class)
public class ExampleAuthzConfiguration {

    /**
     * The principal's type-level ceiling — the one prerequisite the module has no default for. Registered
     * raw: the starter's memo post-processor decorates it under {@code opa.abac.resolve-memo.enabled}, so the
     * extra fan-out it performs is paid at most once per request per target type.
     */
    @Bean
    PrincipalCeilingSupplier principalCeilingSupplier(
            ObjectMapper objectMapper, ExampleAuthzProperties properties) {
        ExampleAuthzProperties.RoleSource source = properties.getRoleSource();
        return new TypeLevelPrincipalCeilingSupplier(
                objectMapper, source.getBaseUrl(), source.getTimeout(), source.getGrantScopeTypes());
    }

    /**
     * Installs the roster filter — <strong>conditionally</strong>, unlike the call-time gate.
     *
     * <p>The asymmetry is the design: the gate has no off switch because it is authoritative, while the
     * roster is a hint whose adapter reflects into pinned SDK internals. When an upgrade moves them the
     * installer fails startup by design, and this switch is the escape hatch that lets the server boot
     * anyway — serving the unfiltered list with call-time enforcement untouched, i.e. exactly the
     * outside-the-batch degradation path. OFF is therefore never wider than ON.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "example.mcp.authz.roster-filter", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    RosterFilterInstaller rosterFilterInstaller(
            ObjectProvider<McpStreamableServerTransportProvider> transportProviders,
            ToolRosterFilter filter) {
        return new RosterFilterInstaller(transportProviders, filter);
    }
}
