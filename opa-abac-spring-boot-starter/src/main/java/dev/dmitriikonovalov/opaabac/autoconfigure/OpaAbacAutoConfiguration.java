package dev.dmitriikonovalov.opaabac.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.HttpOpaClient;
import dev.dmitriikonovalov.opaabac.core.NoOpRoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClientConfig;
import dev.dmitriikonovalov.opaabac.core.PerTypePolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.data.filter.ResidualSpecificationFactory;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalAuthorizer;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreeAncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreePathSource;
import dev.dmitriikonovalov.opaabac.data.hierarchy.ParentLinkSource;
import dev.dmitriikonovalov.opaabac.data.hierarchy.RecursiveCteAncestorResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the OPA ABAC starter: "add the dependency + a few properties" turns the spine
 * on. Every bean is {@link ConditionalOnMissingBean} (the app overrides any of them); the whole config
 * is gated on {@code opa.abac.enabled} (default on).
 *
 * <p><strong>Module-aware:</strong> the core beans (OPA client, policy resolver, role-definition
 * supplier) are always available; the Spring-Security beans live in a nested config gated on the
 * security/web classes being present, so this starter is usable in a non-web app too.
 *
 * <p>This config <strong>does not register a {@code SecurityFilterChain}</strong> — that is the
 * application's job. It exposes beans (incl. {@code AbacFilter}) for the app to install in its own chain.
 */
@AutoConfiguration
@EnableConfigurationProperties(OpaAbacProperties.class)
@ConditionalOnProperty(prefix = "opa.abac", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpaAbacAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PolicyPathResolver policyPathResolver(OpaAbacProperties properties) {
        return new PerTypePolicyPathResolver(properties.getPolicyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public OpaClient opaClient(
            ObjectProvider<ObjectMapper> objectMapper,
            PolicyPathResolver policyPathResolver,
            OpaAbacProperties properties) {
        // Reuse the application's ObjectMapper if present; otherwise a private one. The starter must NOT
        // register a primary ObjectMapper bean — that would suppress Boot's Jackson auto-configuration
        // (e.g. JSR-310 date support) for the whole app.
        ObjectMapper mapper = objectMapper.getIfAvailable(ObjectMapper::new);
        OpaClientConfig config = new OpaClientConfig(
                properties.getBaseUrl(), properties.getTimeout(), properties.getDecisionField());
        return new HttpOpaClient(mapper, policyPathResolver, config);
    }

    /**
     * The library's no-op default. An application overrides this with a real supplier (a static demo
     * supplier, or — in a later phase — one backed by an authority service) and the starter backs off.
     */
    @Bean
    @ConditionalOnMissingBean
    public RoleDefinitionSupplier roleDefinitionSupplier() {
        return new NoOpRoleDefinitionSupplier();
    }

    /**
     * Spring-Security beans, present only when the security + web classes are on the classpath. Wires
     * the JWT extractor, the {@code AbacFilter}, the {@code @OpaPreAuthorize} manager + advisor, and the
     * opt-in request-level manager. Still no {@code SecurityFilterChain}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "org.springframework.security.web.SecurityFilterChain",
        "org.springframework.web.filter.OncePerRequestFilter"
    })
    @org.springframework.context.annotation.Import(OpaAbacSecurityBeans.class)
    static class SecurityAutoConfiguration {
    }

    /**
     * Data-filtering beans (partial-eval → JPA {@code Specification}), present only when Spring Data JPA is
     * on the classpath. Security-independent — they need JPA, not the web/security stack. The
     * {@code AbacQueryService} carries the {@code partialEval.enabled} kill-switch and the
     * {@code allowlistFallback} toggle; both default on.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaSpecificationExecutor")
    static class DataFilteringAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ResidualSpecificationFactory residualSpecificationFactory() {
            return new ResidualSpecificationFactory();
        }

        @Bean
        @ConditionalOnMissingBean
        public AbacQueryService abacQueryService(
                OpaClient opaClient,
                ResidualSpecificationFactory residualSpecificationFactory,
                OpaAbacProperties properties) {
            OpaAbacProperties.PartialEval pe = properties.getPartialEval();
            return new AbacQueryService(
                    opaClient,
                    residualSpecificationFactory,
                    new AbacQueryService.PartialEvalSettings(pe.isEnabled(), pe.isAllowlistFallback()));
        }
    }

    /**
     * Hierarchical (N-level ancestor) authorization beans (Slice 5.5-A). <strong>Opt-in, default-off</strong>:
     * the whole group is gated on {@code opa.abac.hierarchy.enabled=true} AND Spring Data JPA on the
     * classpath. The {@code AncestorResolver} is chosen by {@code hierarchy.resolver} ({@code ltree}/{@code cte})
     * and wired only when the app supplies the matching data-access source bean — a
     * {@link LtreePathSource} for ltree or a {@link ParentLinkSource} for cte (the library can't know the
     * app's tables). The app can also supply its own {@code AncestorResolver} / {@code HierarchicalAuthorizer}
     * to override everything ({@link ConditionalOnMissingBean}).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaSpecificationExecutor")
    @ConditionalOnProperty(prefix = "opa.abac.hierarchy", name = "enabled", havingValue = "true")
    static class HierarchyAutoConfiguration {

        /** The default resolver — used when {@code hierarchy.resolver=ltree} and an app supplies a path source. */
        @Bean
        @ConditionalOnMissingBean(AncestorResolver.class)
        @ConditionalOnBean(LtreePathSource.class)
        @ConditionalOnProperty(prefix = "opa.abac.hierarchy", name = "resolver",
                havingValue = "ltree", matchIfMissing = true)
        public AncestorResolver ltreeAncestorResolver(
                LtreePathSource pathSource, OpaAbacProperties properties) {
            return new LtreeAncestorResolver(pathSource, properties.getHierarchy().getMaxDepth());
        }

        /** The live-walk resolver — used when {@code hierarchy.resolver=cte} and an app supplies a parent source. */
        @Bean
        @ConditionalOnMissingBean(AncestorResolver.class)
        @ConditionalOnBean(ParentLinkSource.class)
        @ConditionalOnProperty(prefix = "opa.abac.hierarchy", name = "resolver", havingValue = "cte")
        public AncestorResolver recursiveCteAncestorResolver(
                ParentLinkSource parentSource, OpaAbacProperties properties) {
            return new RecursiveCteAncestorResolver(parentSource, properties.getHierarchy().getMaxDepth());
        }

        /** The single-resource hierarchical check, wired once a resolver is present. */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(AncestorResolver.class)
        public HierarchicalAuthorizer hierarchicalAuthorizer(
                AncestorResolver ancestorResolver,
                RoleDefinitionSupplier roleDefinitionSupplier,
                OpaClient opaClient) {
            return new HierarchicalAuthorizer(ancestorResolver, roleDefinitionSupplier, opaClient);
        }
    }
}
