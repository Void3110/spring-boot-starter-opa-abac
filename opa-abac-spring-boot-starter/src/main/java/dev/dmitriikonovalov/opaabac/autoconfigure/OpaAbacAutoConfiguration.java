package dev.dmitriikonovalov.opaabac.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.AbacResourceResolver;
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
import dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver;
import dev.dmitriikonovalov.opaabac.security.RequestAttributesResourceCache;
import dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
    public OpaClient opaClient(PolicyPathResolver policyPathResolver, OpaAbacProperties properties) {
        // A PRIVATE ObjectMapper, deliberately not the application's: the serialized `input` is the wire
        // protocol the policies match on, so it must not silently change with app-level Jackson
        // customizations (naming strategies, inclusion rules, custom modules) — a global snake_case
        // switch would break every policy match. Fail-closed turns that into an outage, not a breach,
        // but the contract belongs to the starter, not to whoever last touched the app's mapper.
        // (No ObjectMapper bean is registered either — Boot's Jackson auto-config stays untouched.)
        OpaClientConfig config = new OpaClientConfig(
                properties.getBaseUrl(), properties.getTimeout(), properties.getDecisionField());
        return new HttpOpaClient(new ObjectMapper(), policyPathResolver, config);
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
                OpaAbacProperties properties,
                ObjectProvider<AncestorResolver> ancestorResolver) {
            OpaAbacProperties.PartialEval pe = properties.getPartialEval();
            // The AncestorResolver is present only when hierarchy is enabled (5.5-A wiring). When absent, the
            // allowlist-batch path decides each row on its direct grant only (fail-closed, just not
            // hierarchy-aware). When present, the batch path carries each row's ancestor chain (5.5-B).
            return new AbacQueryService(
                    opaClient,
                    residualSpecificationFactory,
                    new AbacQueryService.PartialEvalSettings(pe.isEnabled(), pe.isAllowlistFallback()),
                    ancestorResolver.getIfAvailable());
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

        /**
         * The <strong>list</strong> widening resolver (Slice 5.5-B), wired once a resolver is present. It
         * produces the {@code subtreeSpec} the example list authorizers pass into the 4-arg
         * {@code AbacQueryService.findAuthorized}. It reuses the same {@code AncestorResolver} +
         * {@code RoleDefinitionSupplier} as the single-resource authorizer, plus the inheritance declaration
         * (the {@code childType -> [ancestorType…]} map). Overridable via {@link ConditionalOnMissingBean}.
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(AncestorResolver.class)
        public SubtreeSpecResolver subtreeSpecResolver(
                AncestorResolver ancestorResolver,
                RoleDefinitionSupplier roleDefinitionSupplier,
                OpaAbacProperties properties) {
            return new SubtreeSpecResolver(
                    ancestorResolver, roleDefinitionSupplier, properties.getHierarchy().getInheritable());
        }
    }

    /**
     * Resource-resolution (attribute-rich pre-authorization) beans (Phase 5.97). <strong>Opt-in by bean
     * presence</strong>: active only when the app registers an {@link AbacResourceResolver} — and the
     * {@code opa.abac.resource-resolution.enabled} kill-switch (default on) hasn't turned it off. With
     * the condition unmet, no {@code ResourceResolutionSupport} bean exists and the
     * {@code @OpaPreAuthorize} manager is wired exactly as before (the rollback path of ADR 0013).
     * When a 5.5 {@code AncestorResolver} bean is present (hierarchy enabled), the gate's
     * {@code AncestorChainSupplier} is bound to it — the same {@code ObjectProvider} idiom as the
     * data-filtering wiring; hierarchy off → no supplier → the chain is always empty (flat resources).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "org.springframework.security.web.SecurityFilterChain",
        "org.springframework.web.filter.OncePerRequestFilter"
    })
    @ConditionalOnBean(AbacResourceResolver.class)
    @ConditionalOnProperty(prefix = "opa.abac.resource-resolution", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class ResourceResolutionAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AbacResourceCache abacResourceCache() {
            return new RequestAttributesResourceCache();
        }

        @Bean
        @ConditionalOnMissingBean
        public ResourceResolutionSupport resourceResolutionSupport(
                AbacResourceResolver resolver,
                ObjectProvider<AncestorResolver> ancestorResolver,
                AbacResourceCache cache) {
            AncestorResolver hierarchyResolver = ancestorResolver.getIfAvailable();
            return new ResourceResolutionSupport(
                    resolver, hierarchyResolver != null ? hierarchyResolver::ancestorsOf : null, cache);
        }
    }

    /**
     * The shared persistence-conflict mapping (retro-audit fold-in #1): optimistic-lock races and
     * constraint violations answer {@code 409 STATE_CONFLICT} problem+json instead of {@code 500} —
     * for every web adopter with Spring-DAO on the classpath, zero per-service code. Guarded by
     * {@code @ConditionalOnClass} so non-JPA adopters never load the DAO exception types; a
     * user-supplied bean overrides it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.dao.OptimisticLockingFailureException")
    static class PersistenceConflictAdviceAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public PersistenceConflictProblemAdvice persistenceConflictProblemAdvice() {
            return new PersistenceConflictProblemAdvice();
        }
    }

    /**
     * The library not-found mapping (retro-audit follow-up): {@code AbstractCrudService}'s
     * {@link dev.dmitriikonovalov.opaabac.data.service.EntityNotFoundException} answers
     * {@code 404 RESOURCE_NOT_FOUND} problem+json instead of {@code 500} (the update-vs-delete race).
     * Guarded by {@code @ConditionalOnClass} so adopters without the spring-data module never load the
     * exception type; a user-supplied bean overrides it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "dev.dmitriikonovalov.opaabac.data.service.EntityNotFoundException")
    static class EntityNotFoundAdviceAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public EntityNotFoundProblemAdvice entityNotFoundProblemAdvice() {
            return new EntityNotFoundProblemAdvice();
        }
    }
}
