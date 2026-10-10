package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;
import dev.dmitriikonovalov.opaabac.mcp.authz.MemoizingPrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolAuthorizationProperties;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolPolicyPathResolver;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolRosterFilter;
import dev.dmitriikonovalov.opaabac.mcp.identity.ActorClaimWiringCheck;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProperties;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.opaabac.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.ConfigAgentCapabilitySupplier;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.IdentityProperties;
import dev.dmitriikonovalov.opaabac.mcp.identity.TurnScopedCapabilityCache;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auto-configuration for {@code opa-abac-mcp}, the agent tool-gate (ADR 0036): adding the module next to
 * Spring AI's MCP server is the whole installation. The starter builds every gate bean, decorates what must
 * be decorated, wraps the tool specifications, and <strong>refuses to start</strong> — naming the fix —
 * when a prerequisite is missing.
 *
 * <h2>Never present-but-inert</h2>
 * Activated by the module and Spring AI on the classpath (both by name — the starter carries neither as a
 * dependency), and deliberately <strong>not</strong> gated on {@code opa.abac.enabled}: with the starter
 * switched off there is no {@code OpaClient}, and {@link McpStartupVerifier} fails startup rather than
 * leaving the tools ungated — even under {@code opa.abac.allow-ungated-methods=true} (ADR 0036 §12). No
 * property disables the gate; {@code opa.abac.mcp.agent-gate.enabled=false} drops the agent conjunct only.
 *
 * <h2>What an adopter provides, and what is defaulted</h2>
 * Provided: a {@link PrincipalCeilingSupplier} (no default) and a {@link ToolRegistry}. Defaulted, each
 * {@code @ConditionalOnMissingBean}: the {@link ToolCallAuthorizer}, the {@link ToolRosterFilter}, the
 * {@link DelegationChainExtractor} (the claim-based extractor over a <strong>private</strong>
 * {@link JsonMapper} — a claim parser must not change with the application's Jackson customizations, the
 * {@code OpaClient} precedent) and the YAML {@link AgentCapabilitySupplier}.
 *
 * <h2>Three bean-level post-processors</h2>
 * Bean-level (the {@code OpaResolveMemoAutoConfiguration} idiom), so an adopter's bean is decorated too
 * and every consumer shares one instance: the memos (ceiling — under {@code opa.abac.resolve-memo.enabled};
 * capability — always, so the roster and the gate share one answer per turn), the policy-path decorator
 * (resource type {@code tool} ⇒ {@code opa.abac.mcp.policy-path}, every other type ⇒ the application's own
 * resolver — the type {@code tool} is reserved), and the gate itself.
 */
@AutoConfiguration(after = OpaAbacAutoConfiguration.class)
@ConditionalOnClass(name = {
    "dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer",
    "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration"
})
@EnableConfigurationProperties({
    ToolAuthorizationProperties.class, IdentityProperties.class, AgentCapabilityProperties.class
})
public class OpaMcpAutoConfiguration {

    /** Read by the ceiling memo exactly as the role-definition memo reads it (default on). */
    static final String RESOLVE_MEMO_ENABLED = "opa.abac.resolve-memo.enabled";

    /** The claim names the starter copies into the subject — read from the environment, not its bean. */
    static final String ATTRIBUTE_CLAIMS = "opa.abac.subject.attribute-claims";

    /**
     * The PEP. Its prerequisites arrive through {@link ObjectProvider#getIfAvailable()}, never as hard
     * dependencies: a missing {@code OpaClient} or ceiling source must surface as the verifier's named
     * failure, not as Spring's generic {@code UnsatisfiedDependencyException}.
     */
    @Bean
    @ConditionalOnMissingBean
    ToolCallAuthorizer toolCallAuthorizer(
            ObjectProvider<ToolRegistry> registry,
            DelegationChainExtractor delegationChainExtractor,
            AgentCapabilitySupplier capabilitySupplier,
            ObjectProvider<PrincipalCeilingSupplier> ceilingSupplier,
            ObjectProvider<OpaClient> opaClient,
            ToolAuthorizationProperties properties) {
        return new ToolCallAuthorizer(
                registry.getIfAvailable(),
                delegationChainExtractor,
                capabilitySupplier,
                ceilingSupplier.getIfAvailable(),
                opaClient.getIfAvailable(),
                properties);
    }

    @Bean
    @ConditionalOnMissingBean
    ToolRosterFilter toolRosterFilter(
            ObjectProvider<ToolRegistry> registry,
            ToolCallAuthorizer authorizer,
            ObjectProvider<OpaClient> opaClient,
            ToolAuthorizationProperties properties) {
        return new ToolRosterFilter(registry.getIfAvailable(), authorizer, opaClient.getIfAvailable(), properties);
    }

    @Bean
    @ConditionalOnMissingBean
    DelegationChainExtractor delegationChainExtractor(IdentityProperties properties) {
        return new ClaimDelegationChainExtractor(JsonMapper.builder().build(), properties);
    }

    /** The YAML default ({@code opa.abac.mcp.agents.profiles[]}); the memo post-processor wraps it. */
    @Bean
    @ConditionalOnMissingBean
    AgentCapabilitySupplier agentCapabilitySupplier(AgentCapabilityProperties properties) {
        return new ConfigAgentCapabilitySupplier(properties);
    }

    /**
     * Always registered: the actor claim must reach the subject, or every agent call reads as a human one.
     * The copied claims are read through a {@link Binder} (the {@code OpaAbacDisabledAutoConfiguration}
     * precedent), so the check never depends on the starter's properties bean — it runs with the starter off.
     */
    @Bean
    ActorClaimWiringCheck actorClaimWiringCheck(IdentityProperties properties, Environment environment) {
        List<String> copiedClaims = Binder.get(environment)
                .bind(ATTRIBUTE_CLAIMS, Bindable.listOf(String.class))
                .orElse(List.of());
        return new ActorClaimWiringCheck(properties, copiedClaims);
    }

    /** The single startup guard — prerequisites, then every singleton list resolved and asserted gated. */
    @Bean
    McpStartupVerifier opaMcpStartupVerifier(
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<OpaClient> opaClient,
            ObjectProvider<PrincipalCeilingSupplier> ceilingSupplier) {
        return new McpStartupVerifier(beanFactory, opaClient, ceilingSupplier);
    }

    /**
     * The memos, bean-level: any {@link PrincipalCeilingSupplier} → {@link MemoizingPrincipalCeilingSupplier}
     * under {@code opa.abac.resolve-memo.enabled} (default on, the role-definition memo's switch), and any
     * {@link AgentCapabilitySupplier} → {@link TurnScopedCapabilityCache} (always — the roster and the gate
     * must see one capability answer per turn). Each guarded against double-wrapping.
     */
    @Bean
    static BeanPostProcessor opaMcpMemoDecorator(Environment environment) {
        // Read exactly as the role-definition memo's @ConditionalOnProperty(havingValue = "true",
        // matchIfMissing = true) reads it, so the two memos can never disagree on one switch value.
        boolean ceilingMemo = "true".equalsIgnoreCase(environment.getProperty(RESOLVE_MEMO_ENABLED, "true"));
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (ceilingMemo && bean instanceof PrincipalCeilingSupplier delegate
                        && !(bean instanceof MemoizingPrincipalCeilingSupplier)) {
                    return new MemoizingPrincipalCeilingSupplier(delegate);
                }
                if (bean instanceof AgentCapabilitySupplier delegate
                        && !(bean instanceof TurnScopedCapabilityCache)) {
                    return new TurnScopedCapabilityCache(delegate);
                }
                return bean;
            }
        };
    }

    /**
     * Decorates — never replaces — the context's {@link PolicyPathResolver}: {@code tool} ⇒ the tool-gate
     * document, every other type ⇒ the application's resolver unchanged. The starter's one {@code OpaClient}
     * is built from this bean, so every other gate in the application keeps its routing.
     */
    @Bean
    static BeanPostProcessor opaMcpPolicyPathDecorator(ObjectProvider<ToolAuthorizationProperties> properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof PolicyPathResolver delegate && !(bean instanceof ToolPolicyPathResolver)) {
                    return new ToolPolicyPathResolver(delegate, properties.getObject());
                }
                return bean;
            }
        };
    }

    /** The gate on every tool specification list — see {@link McpToolGatePostProcessor}. */
    @Bean
    static McpToolGatePostProcessor opaMcpToolGate(ObjectProvider<ToolCallAuthorizer> authorizer) {
        return new McpToolGatePostProcessor(authorizer);
    }
}
