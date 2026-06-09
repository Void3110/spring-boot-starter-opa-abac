package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.NoOpRoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PerTypePolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.data.filter.ResidualSpecificationFactory;
import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorizeAuthorizationManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.security.web.SecurityFilterChain;

/** {@link ApplicationContextRunner} slice tests for the starter — QA cases U30–U34. */
class OpaAbacAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpaAbacAutoConfiguration.class));

    @Test // U30 — enabled + security on classpath → all spine beans present
    void allBeansPresent_whenEnabled() {
        runner.withPropertyValues("opa.abac.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OpaClient.class);
            assertThat(context).hasSingleBean(PolicyPathResolver.class);
            assertThat(context).hasSingleBean(RoleDefinitionSupplier.class);
            assertThat(context).hasSingleBean(AbacSubjectExtractor.class);
            assertThat(context).hasSingleBean(AbacFilter.class);
            assertThat(context).hasSingleBean(OpaPreAuthorizeAuthorizationManager.class);
            // the starter never creates a SecurityFilterChain
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }

    @Test // U31 — disabled → no spine beans
    void noBeans_whenDisabled() {
        runner.withPropertyValues("opa.abac.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OpaClient.class);
            assertThat(context).doesNotHaveBean(PolicyPathResolver.class);
            assertThat(context).doesNotHaveBean(RoleDefinitionSupplier.class);
            assertThat(context).doesNotHaveBean(AbacFilter.class);
        });
    }

    @Test // U32 — user @Bean OpaClient / RoleDefinitionSupplier → starter backs off
    void userBeansWin() {
        runner.withUserConfiguration(UserOverrides.class).run(context -> {
            assertThat(context).hasSingleBean(OpaClient.class);
            assertThat(context.getBean(OpaClient.class)).isInstanceOf(StubOpaClient.class);
            assertThat(context.getBean(RoleDefinitionSupplier.class)).isInstanceOf(StubSupplier.class);
        });
    }

    @Test // U33 — security/web removed → only the core beans, no security beans, no chain
    void onlyCoreBeans_whenSecurityAbsent() {
        runner.withClassLoader(new FilteredClassLoader(
                org.springframework.security.web.SecurityFilterChain.class,
                org.springframework.web.filter.OncePerRequestFilter.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(OpaClient.class);
                    assertThat(context).hasSingleBean(PolicyPathResolver.class);
                    assertThat(context).hasSingleBean(RoleDefinitionSupplier.class);
                    assertThat(context).doesNotHaveBean(AbacSubjectExtractor.class);
                    assertThat(context).doesNotHaveBean(AbacFilter.class);
                    assertThat(context).doesNotHaveBean(OpaPreAuthorizeAuthorizationManager.class);
                });
    }

    @Test // U34 — property binding
    void propertiesBind() {
        runner.withPropertyValues(
                "opa.abac.base-url=http://opa:8181",
                "opa.abac.decision-field=permit",
                "opa.abac.policy-prefix=catalog",
                "opa.abac.subject.roles-claim=authz.groups",
                "opa.abac.subject.validate-expiry=false")
                .run(context -> {
                    OpaAbacProperties props = context.getBean(OpaAbacProperties.class);
                    assertThat(props.getBaseUrl()).isEqualTo("http://opa:8181");
                    assertThat(props.getDecisionField()).isEqualTo("permit");
                    assertThat(props.getPolicyPrefix()).isEqualTo("catalog");
                    assertThat(props.getSubject().getRolesClaim()).isEqualTo("authz.groups");
                    assertThat(props.getSubject().isValidateExpiry()).isFalse();
                    // resolver reflects the prefix
                    assertThat(context.getBean(PolicyPathResolver.class)).isInstanceOf(PerTypePolicyPathResolver.class);
                });
    }

    @Test // default supplier is the no-op
    void defaultSupplier_isNoOp() {
        runner.run(context ->
                assertThat(context.getBean(RoleDefinitionSupplier.class)).isInstanceOf(NoOpRoleDefinitionSupplier.class));
    }

    // --- data-filtering beans (T5) -------------------------------------------

    @Test // I3 — JPA on classpath + enabled → the filtering beans are present
    void dataFilteringBeansPresent_withJpa() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ResidualSpecificationFactory.class);
            assertThat(context).hasSingleBean(AbacQueryService.class);
        });
    }

    @Test // I4 — JPA absent → the filtering beans are absent (security/core unaffected)
    void dataFilteringBeansAbsent_withoutJpa() {
        runner.withClassLoader(new FilteredClassLoader(JpaSpecificationExecutor.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ResidualSpecificationFactory.class);
                    assertThat(context).doesNotHaveBean(AbacQueryService.class);
                    // the rest of the spine is unaffected
                    assertThat(context).hasSingleBean(OpaClient.class);
                });
    }

    @Test // I5 — a user-supplied factory / query service overrides the auto one
    void userDataFilteringBeansWin() {
        runner.withUserConfiguration(DataFilteringOverrides.class).run(context -> {
            assertThat(context.getBean(ResidualSpecificationFactory.class))
                    .isSameAs(DataFilteringOverrides.FACTORY);
            assertThat(context.getBean(AbacQueryService.class))
                    .isInstanceOf(StubQueryService.class);
        });
    }

    @Test // I6 — partialEval properties bind
    void partialEvalPropertiesBind() {
        runner.withPropertyValues(
                "opa.abac.partial-eval.enabled=false",
                "opa.abac.partial-eval.allowlist-fallback=false")
                .run(context -> {
                    OpaAbacProperties props = context.getBean(OpaAbacProperties.class);
                    assertThat(props.getPartialEval().isEnabled()).isFalse();
                    assertThat(props.getPartialEval().isAllowlistFallback()).isFalse();
                });
    }

    @Test // I6b — partialEval defaults are both on
    void partialEvalDefaults_areOn() {
        runner.run(context -> {
            OpaAbacProperties props = context.getBean(OpaAbacProperties.class);
            assertThat(props.getPartialEval().isEnabled()).isTrue();
            assertThat(props.getPartialEval().isAllowlistFallback()).isTrue();
        });
    }

    // --- hierarchy beans (T5) ------------------------------------------------

    @Test // default-off: no hierarchy beans unless hierarchy.enabled=true (even with a source present)
    void hierarchyBeansAbsent_byDefault() {
        runner.withUserConfiguration(LtreeSourceConfig.class).run(context -> {
            assertThat(context).doesNotHaveBean(
                    dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver.class);
            assertThat(context).doesNotHaveBean(
                    dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalAuthorizer.class);
        });
    }

    @Test // enabled + an ltree path source → the ltree resolver + the authorizer
    void hierarchyLtreeResolver_whenEnabledWithPathSource() {
        runner.withPropertyValues("opa.abac.hierarchy.enabled=true")
                .withUserConfiguration(LtreeSourceConfig.class)
                .run(context -> {
                    assertThat(context.getBean(dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver.class))
                            .isInstanceOf(dev.dmitriikonovalov.opaabac.data.hierarchy.LtreeAncestorResolver.class);
                    assertThat(context).hasSingleBean(
                            dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalAuthorizer.class);
                });
    }

    @Test // resolver=cte + a parent-link source → the recursive-CTE resolver
    void hierarchyCteResolver_whenSelectedWithParentSource() {
        runner.withPropertyValues(
                        "opa.abac.hierarchy.enabled=true",
                        "opa.abac.hierarchy.resolver=cte")
                .withUserConfiguration(CteSourceConfig.class)
                .run(context -> assertThat(
                        context.getBean(dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver.class))
                        .isInstanceOf(
                                dev.dmitriikonovalov.opaabac.data.hierarchy.RecursiveCteAncestorResolver.class));
    }

    @Test // enabled but NO source bean → no resolver (the app must supply the data-access seam)
    void hierarchyResolverAbsent_withoutSource() {
        runner.withPropertyValues("opa.abac.hierarchy.enabled=true").run(context ->
                assertThat(context).doesNotHaveBean(
                        dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver.class));
    }

    @Test // an app-supplied AncestorResolver overrides the auto one
    void userAncestorResolverWins() {
        runner.withPropertyValues("opa.abac.hierarchy.enabled=true")
                .withUserConfiguration(LtreeSourceConfig.class, UserResolverConfig.class)
                .run(context -> assertThat(
                        context.getBean(dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver.class))
                        .isSameAs(UserResolverConfig.RESOLVER));
    }

    @Test // U13 (5.5-B) — enabled + a source → the SubtreeSpecResolver (list widening) bean is present
    void subtreeSpecResolver_whenEnabledWithSource() {
        runner.withPropertyValues("opa.abac.hierarchy.enabled=true")
                .withUserConfiguration(LtreeSourceConfig.class)
                .run(context -> assertThat(context).hasSingleBean(
                        dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver.class));
    }

    @Test // U13 — no SubtreeSpecResolver when hierarchy is off (default), even with a source present
    void subtreeSpecResolverAbsent_byDefault() {
        runner.withUserConfiguration(LtreeSourceConfig.class).run(context ->
                assertThat(context).doesNotHaveBean(
                        dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver.class));
    }

    @Test // U14 — an app-supplied SubtreeSpecResolver overrides the auto one
    void userSubtreeSpecResolverWins() {
        runner.withPropertyValues("opa.abac.hierarchy.enabled=true")
                .withUserConfiguration(LtreeSourceConfig.class, UserSubtreeResolverConfig.class)
                .run(context -> assertThat(
                        context.getBean(dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver.class))
                        .isSameAs(UserSubtreeResolverConfig.RESOLVER));
    }

    @Test // hierarchy properties bind (maxDepth + the inheritable map)
    void hierarchyPropertiesBind() {
        runner.withPropertyValues(
                        "opa.abac.hierarchy.enabled=true",
                        "opa.abac.hierarchy.resolver=cte",
                        "opa.abac.hierarchy.max-depth=8",
                        "opa.abac.hierarchy.inheritable.category=catalog",
                        "opa.abac.hierarchy.inheritable.product=category,catalog")
                .run(context -> {
                    OpaAbacProperties props = context.getBean(OpaAbacProperties.class);
                    assertThat(props.getHierarchy().isEnabled()).isTrue();
                    assertThat(props.getHierarchy().getResolver()).isEqualTo("cte");
                    assertThat(props.getHierarchy().getMaxDepth()).isEqualTo(8);
                    assertThat(props.getHierarchy().getInheritable())
                            .containsEntry("category", java.util.List.of("catalog"))
                            .containsEntry("product", java.util.List.of("category", "catalog"));
                });
    }

    @Test // hierarchy defaults: off, ltree, maxDepth 32, empty inheritable
    void hierarchyDefaults() {
        runner.run(context -> {
            OpaAbacProperties.Hierarchy h = context.getBean(OpaAbacProperties.class).getHierarchy();
            assertThat(h.isEnabled()).isFalse();
            assertThat(h.getResolver()).isEqualTo("ltree");
            assertThat(h.getMaxDepth()).isEqualTo(32);
            assertThat(h.getInheritable()).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class LtreeSourceConfig {
        @Bean
        dev.dmitriikonovalov.opaabac.data.hierarchy.LtreePathSource ltreePathSource() {
            return (type, id) -> Optional.empty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CteSourceConfig {
        @Bean
        dev.dmitriikonovalov.opaabac.data.hierarchy.ParentLinkSource parentLinkSource() {
            return (type, id) -> Optional.empty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserResolverConfig {
        // AncestorResolver is no longer a single-method interface (it gained subtreeOf), so the user-supplied
        // override is an anonymous class: an empty chain and a fail-closed (empty) subtree predicate.
        static final dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver RESOLVER =
                new dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver() {
                    @Override
                    public java.util.List<dev.dmitriikonovalov.opaabac.core.ParentRef> ancestorsOf(
                            String leafType, String leafId) {
                        return java.util.List.of();
                    }

                    @Override
                    public <T> org.springframework.data.jpa.domain.Specification<T> subtreeOf(
                            String rootType, String rootId) {
                        return (root, query, cb) -> cb.disjunction();
                    }
                };

        @Bean
        dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver ancestorResolver() {
            return RESOLVER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSubtreeResolverConfig {
        static final dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver RESOLVER =
                new dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver(
                        UserResolverConfig.RESOLVER,
                        (userId, type, id) -> java.util.Optional.empty(),
                        java.util.Map.of());

        @Bean
        dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver subtreeSpecResolver() {
            return RESOLVER;
        }
    }

    // --- user overrides ------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class UserOverrides {
        @Bean
        OpaClient opaClient() {
            return new StubOpaClient();
        }

        @Bean
        RoleDefinitionSupplier roleDefinitionSupplier() {
            return new StubSupplier();
        }
    }

    static class StubOpaClient implements OpaClient {
        @Override
        public boolean allow(dev.dmitriikonovalov.opaabac.core.AbacContext context) {
            return true;
        }

        @Override
        public dev.dmitriikonovalov.opaabac.core.PartialResult compile(
                dev.dmitriikonovalov.opaabac.core.AbacContext context) {
            return dev.dmitriikonovalov.opaabac.core.PartialResult.denyAll();
        }

        @Override
        public java.util.List<Boolean> allowAll(
                java.util.List<dev.dmitriikonovalov.opaabac.core.AbacContext> contexts) {
            return java.util.Collections.nCopies(contexts.size(), Boolean.FALSE);
        }
    }

    static class StubSupplier implements RoleDefinitionSupplier {
        @Override
        public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
            return Optional.empty();
        }
    }

    // --- data-filtering overrides (I5) ---------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class DataFilteringOverrides {
        static final ResidualSpecificationFactory FACTORY = new ResidualSpecificationFactory();

        @Bean
        ResidualSpecificationFactory residualSpecificationFactory() {
            return FACTORY;
        }

        @Bean
        AbacQueryService abacQueryService() {
            return new StubQueryService();
        }
    }

    /** A user-supplied AbacQueryService that must win over the starter's auto one. */
    static class StubQueryService extends AbacQueryService {
        StubQueryService() {
            super(new StubOpaClient(), new ResidualSpecificationFactory(),
                    AbacQueryService.PartialEvalSettings.defaults());
        }
    }
}
