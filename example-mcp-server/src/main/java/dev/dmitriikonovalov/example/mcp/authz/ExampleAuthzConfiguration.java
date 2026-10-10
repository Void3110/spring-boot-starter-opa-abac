package dev.dmitriikonovalov.example.mcp.authz;

import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;
import dev.dmitriikonovalov.opaabac.mcp.authz.MemoizingPrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolAuthorizationProperties;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallGate;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolPolicyPathResolver;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolRosterFilter;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * Installs the tool-gate from the {@code opa-abac-mcp} module's types, plus the example's own pieces:
 * its ceiling source and the reflective roster installer.
 *
 * <p>The gate is applied by post-processing the tool-specification list Spring AI's annotation scanner
 * produces, so <strong>every</strong> advertised tool is wrapped by construction — a tool added later
 * cannot be forgotten, because nothing here enumerates tool names. There is deliberately no property
 * that disables this wrapping: {@code opa.abac.mcp.agent-gate.enabled} controls whether the gate
 * <em>narrows</em>, never whether it <em>runs</em>.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ToolAuthorizationProperties.class, ExampleAuthzProperties.class})
public class ExampleAuthzConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExampleAuthzConfiguration.class);

    @Bean
    PolicyPathResolver toolPolicyPathResolver(ToolAuthorizationProperties properties) {
        return new ToolPolicyPathResolver(properties);
    }

    /**
     * The principal's type-level ceiling, wrapped in the request memo — so the extra fan-out it performs
     * is paid at most once per request per target type. The memo honors the starter's
     * {@code opa.abac.resolve-memo.enabled} switch (default on), exactly as the starter's memo did when this
     * source was a {@code RoleDefinitionSupplier}: OFF serves the raw supplier.
     */
    @Bean
    PrincipalCeilingSupplier principalCeilingSupplier(
            ObjectMapper objectMapper, ExampleAuthzProperties properties, Environment environment) {
        ExampleAuthzProperties.RoleSource source = properties.getRoleSource();
        PrincipalCeilingSupplier raw = new TypeLevelPrincipalCeilingSupplier(
                objectMapper, source.getBaseUrl(), source.getTimeout(), source.getGrantScopeTypes());
        boolean memoEnabled =
                environment.getProperty("opa.abac.resolve-memo.enabled", Boolean.class, Boolean.TRUE);
        return memoEnabled ? new MemoizingPrincipalCeilingSupplier(raw) : raw;
    }

    @Bean
    ToolCallAuthorizer toolCallAuthorizer(
            ToolRegistry registry,
            DelegationChainExtractor delegationChainExtractor,
            AgentCapabilitySupplier capabilitySupplier,
            PrincipalCeilingSupplier principalCeilingSupplier,
            OpaClient opaClient,
            ToolAuthorizationProperties properties) {
        return new ToolCallAuthorizer(
                registry,
                delegationChainExtractor,
                capabilitySupplier,
                principalCeilingSupplier,
                opaClient,
                properties);
    }

    @Bean
    ToolRosterFilter toolRosterFilter(
            ToolRegistry registry,
            ToolCallAuthorizer authorizer,
            OpaClient opaClient,
            ToolAuthorizationProperties properties) {
        return new ToolRosterFilter(registry, authorizer, opaClient, properties);
    }

    /**
     * Installs the roster filter — <strong>conditionally</strong>, unlike the call-time gate above.
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

    /**
     * Wraps every {@link SyncToolSpecification} the scanner produced with {@link ToolCallGate}.
     *
     * <p>A {@link BeanPostProcessor} rather than a replacement bean: the specification list is built by
     * Spring AI's auto-configuration from the annotated beans, and re-deriving it here would duplicate
     * discovery logic that is not ours to own. Post-processing takes whatever the scanner found — now and
     * after any future upgrade — and gates all of it.
     */
    @Bean
    static BeanPostProcessor toolSpecificationGateDecorator(
            ObjectProvider<ToolCallAuthorizer> authorizerProvider) {
        return new BeanPostProcessor() {
            @Override
            @SuppressWarnings("unchecked")
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (!(bean instanceof List<?> list) || list.isEmpty()
                        || !(list.get(0) instanceof SyncToolSpecification)) {
                    return bean;
                }
                ToolCallAuthorizer authorizer = authorizerProvider.getObject();
                List<SyncToolSpecification> gated = new ArrayList<>(list.size());
                for (SyncToolSpecification specification : (List<SyncToolSpecification>) list) {
                    gated.add(ToolCallGate.gate(specification, authorizer));
                }
                log.info("Tool-gate installed on {} tool specification(s)", gated.size());
                return gated;
            }
        };
    }
}
