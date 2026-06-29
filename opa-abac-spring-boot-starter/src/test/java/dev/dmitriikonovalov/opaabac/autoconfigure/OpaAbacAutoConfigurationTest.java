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

    // --- Slice B4: the ownership resolver is opt-in by abac.ownership.enabled, fail-closed by absence ---

    @Test // ownership disabled (default) → no ResourceOwnershipResolver bean (createTeam fails closed)
    void noOwnershipResolver_byDefault() {
        runner.withPropertyValues("opa.abac.enabled=true").run(context -> assertThat(context)
                .doesNotHaveBean(
                        dev.dmitriikonovalov.opaabac.security.ownership.ResourceOwnershipResolver.class));
    }

    @Test // ownership enabled → the DiscoveryOwnershipResolver is wired, properties bound
    void ownershipResolver_whenEnabled() {
        runner.withPropertyValues(
                        "opa.abac.enabled=true",
                        "abac.ownership.enabled=true",
                        "abac.ownership.services.catalog=http://catalog:8080",
                        "abac.ownership.ttl=45s")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            dev.dmitriikonovalov.opaabac.security.ownership.ResourceOwnershipResolver.class);
                    assertThat(context.getBean(
                            dev.dmitriikonovalov.opaabac.security.ownership.ResourceOwnershipResolver.class))
                            .isInstanceOf(dev.dmitriikonovalov.opaabac.security.ownership
                                    .DiscoveryOwnershipResolver.class);
                    var props = context.getBean(
                            dev.dmitriikonovalov.opaabac.security.ownership.OwnershipProperties.class);
                    assertThat(props.getServices()).containsEntry("catalog", "http://catalog:8080");
                    assertThat(props.getTtl()).isEqualTo(java.time.Duration.ofSeconds(45));
                });
    }

    @Test // U32 — user @Bean OpaClient / RoleDefinitionSupplier → starter backs off. Resilience off here so
    // the assertion sees the raw override (the B3 decorator otherwise wraps it — see userOpaClient_isWrapped).
    void userBeansWin() {
        runner.withPropertyValues("opa.abac.resilience.enabled=false")
                .withUserConfiguration(UserOverrides.class).run(context -> {
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

    // --- trust-forwarded-jwt gate ---------------------------------------------

    @Test // without the explicit trust acknowledgment, the JWT extractor must NOT be wired —
    // the refusing stand-in keeps every request anonymous (fail-closed), never trusts a forwarded token
    void subjectExtractor_refusesUntilTrustAcknowledged() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AbacSubjectExtractor.class);
            assertThat(context.getBean(AbacSubjectExtractor.class))
                    .isNotInstanceOf(dev.dmitriikonovalov.opaabac.security.JwtClaimsSubjectExtractor.class);
            assertThat(context.getBean(OpaAbacProperties.class).getSubject().isTrustForwardedJwt()).isFalse();
        });
    }

    @Test // trust-forwarded-jwt=true → the real JWT extractor is wired
    void subjectExtractor_isJwt_whenTrustAcknowledged() {
        runner.withPropertyValues("opa.abac.subject.trust-forwarded-jwt=true").run(context ->
                assertThat(context.getBean(AbacSubjectExtractor.class))
                        .isInstanceOf(dev.dmitriikonovalov.opaabac.security.JwtClaimsSubjectExtractor.class));
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

    // --- resource-resolution beans (5.97) -------------------------------------

    @Test // U14 — no AbacResourceResolver bean → no support bean; the manager is wired as pre-5.97
    void resourceResolutionAbsent_withoutResolverBean() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(
                    dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport.class);
            assertThat(context).doesNotHaveBean(
                    dev.dmitriikonovalov.opaabac.core.AbacResourceCache.class);
            assertThat(context).hasSingleBean(OpaPreAuthorizeAuthorizationManager.class);
        });
    }

    @Test // U15 — resolver bean registered → support wired; without an AncestorResolver the chain
    // supplier is absent (the gate then always sees an empty chain)
    void resourceResolutionWired_withResolverBean() {
        runner.withUserConfiguration(ResolverConfig.class).run(context -> {
            assertThat(context).hasSingleBean(
                    dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport.class);
            dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport support =
                    context.getBean(dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport.class);
            assertThat(support.resolver()).isSameAs(ResolverConfig.RESOLVER);
            assertThat(support.ancestorChainSupplier()).isNull();
            assertThat(support.cache()).isInstanceOf(
                    dev.dmitriikonovalov.opaabac.security.RequestAttributesResourceCache.class);
        });
    }

    @Test // U15 — with an AncestorResolver bean present, the bound AncestorChainSupplier delegates to it
    void resourceResolutionBindsAncestorResolver_whenPresent() {
        runner.withPropertyValues("opa.abac.hierarchy.enabled=true")
                .withUserConfiguration(ResolverConfig.class, UserResolverConfig.class)
                .run(context -> {
                    dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport support =
                            context.getBean(dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport.class);
                    assertThat(support.ancestorChainSupplier()).isNotNull();
                    // the bound supplier delegates to the AncestorResolver bean — same chain
                    assertThat(support.ancestorChainSupplier().ancestorsOf("category", "c-1"))
                            .isEqualTo(UserResolverConfig.RESOLVER.ancestorsOf("category", "c-1"));
                });
    }

    @Test // U16 — kill-switch off + resolver bean → NO support (baseline restored, beans untouched)
    void resourceResolutionKillSwitch_disablesDespiteResolverBean() {
        runner.withPropertyValues("opa.abac.resource-resolution.enabled=false")
                .withUserConfiguration(ResolverConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport.class);
                    assertThat(context).hasSingleBean(OpaPreAuthorizeAuthorizationManager.class);
                });
    }

    @Test // a user-supplied AbacResourceCache overrides the request-attributes default
    void userResourceCacheWins() {
        runner.withUserConfiguration(ResolverConfig.class, UserCacheConfig.class).run(context ->
                assertThat(context.getBean(dev.dmitriikonovalov.opaabac.core.AbacResourceCache.class))
                        .isSameAs(UserCacheConfig.CACHE));
    }

    @Test // U18 — the kill-switch property binds and defaults to true
    void resourceResolutionPropertyBindsAndDefaultsTrue() {
        runner.run(context -> assertThat(
                context.getBean(OpaAbacProperties.class).getResourceResolution().isEnabled()).isTrue());
        runner.withPropertyValues("opa.abac.resource-resolution.enabled=false").run(context ->
                assertThat(context.getBean(OpaAbacProperties.class).getResourceResolution().isEnabled())
                        .isFalse());
    }

    @Test // U18 — the generated configuration metadata carries the new property
    void configurationMetadataCarriesResourceResolutionProperty() throws Exception {
        try (java.io.InputStream in = getClass()
                .getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("spring-configuration-metadata.json on the classpath").isNotNull();
            String metadata = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(metadata).contains("opa.abac.resource-resolution.enabled");
        }
    }

    // --- OPA resilience decorator (Slice B3, T2) ------------------------------

    @Test // U8 — R4j on classpath + resilience enabled (defaults) → the OpaClient bean is a ResilientOpaClient
    void opaClient_isResilient_whenR4jPresentAndEnabled() {
        runner.run(context -> assertThat(context.getBean(OpaClient.class))
                .isInstanceOf(dev.dmitriikonovalov.opaabac.security.resilience.ResilientOpaClient.class));
    }

    @Test // U8 — R4j absent → the plain HttpOpaClient, byte-identical to pre-B3 (no decorator)
    void opaClient_isPlain_whenR4jAbsent() {
        runner.withClassLoader(new FilteredClassLoader(
                        io.github.resilience4j.circuitbreaker.CircuitBreaker.class))
                .run(context -> {
                    assertThat(context.getBean(OpaClient.class))
                            .isInstanceOf(dev.dmitriikonovalov.opaabac.core.HttpOpaClient.class);
                    assertThat(context.getBean(OpaClient.class)).isNotInstanceOf(
                            dev.dmitriikonovalov.opaabac.security.resilience.ResilientOpaClient.class);
                });
    }

    @Test // U8 — master kill-switch off → the plain HttpOpaClient (R4j present but resilience disabled)
    void opaClient_isPlain_whenResilienceDisabled() {
        runner.withPropertyValues("opa.abac.resilience.enabled=false")
                .run(context -> assertThat(context.getBean(OpaClient.class))
                        .isInstanceOf(dev.dmitriikonovalov.opaabac.core.HttpOpaClient.class)
                        .isNotInstanceOf(
                                dev.dmitriikonovalov.opaabac.security.resilience.ResilientOpaClient.class));
    }

    @Test // U8 — the OPA edge kill-switch off → the plain client (master on, opa edge off)
    void opaClient_isPlain_whenOpaEdgeDisabled() {
        runner.withPropertyValues("opa.abac.resilience.opa.enabled=false")
                .run(context -> assertThat(context.getBean(OpaClient.class))
                        .isNotInstanceOf(
                                dev.dmitriikonovalov.opaabac.security.resilience.ResilientOpaClient.class));
    }

    @Test // a user-supplied OpaClient is still wrapped (the decorator is transparent — adopter bean wins
    // as the delegate, resilience layered on top) — proves the BPP decorates whatever client the context has
    void userOpaClient_isWrapped_whenResilient() {
        runner.withUserConfiguration(UserOverrides.class).run(context -> {
            OpaClient client = context.getBean(OpaClient.class);
            assertThat(client).isInstanceOf(
                    dev.dmitriikonovalov.opaabac.security.resilience.ResilientOpaClient.class);
        });
    }

    @Test // resilience properties bind: per-edge budgets + the master switch
    void resiliencePropertiesBind() {
        runner.withPropertyValues(
                        "opa.abac.resilience.enabled=true",
                        "opa.abac.resilience.opa.max-retries=3",
                        "opa.abac.resilience.opa.ceiling=4s",
                        "opa.abac.resilience.resolve.max-retries=5",
                        "opa.abac.resilience.tag.breaker.failure-threshold=9")
                .run(context -> {
                    OpaAbacProperties.Resilience r = context.getBean(OpaAbacProperties.class).getResilience();
                    assertThat(r.isEnabled()).isTrue();
                    assertThat(r.getOpa().getMaxRetries()).isEqualTo(3);
                    assertThat(r.getOpa().getCeiling()).isEqualTo(java.time.Duration.ofSeconds(4));
                    assertThat(r.getResolve().getMaxRetries()).isEqualTo(5);
                    assertThat(r.getTag().getBreaker().getFailureThreshold()).isEqualTo(9);
                });
    }

    @Test // resilience defaults: master on, asymmetric per-edge budgets (OPA 1 / resolve+tag 2)
    void resilienceDefaults() {
        runner.run(context -> {
            OpaAbacProperties.Resilience r = context.getBean(OpaAbacProperties.class).getResilience();
            assertThat(r.isEnabled()).isTrue();
            assertThat(r.getOpa().getMaxRetries()).isEqualTo(1);
            assertThat(r.getOpa().getCeiling()).isEqualTo(java.time.Duration.ofMillis(2500));
            assertThat(r.getResolve().getMaxRetries()).isEqualTo(2);
            assertThat(r.getResolve().getCeiling()).isEqualTo(java.time.Duration.ofSeconds(6));
            assertThat(r.getTag().getMaxRetries()).isEqualTo(2);
        });
    }

    @Test // the generated configuration metadata carries the new resilience property
    void configurationMetadataCarriesResilienceProperty() throws Exception {
        try (java.io.InputStream in = getClass()
                .getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("spring-configuration-metadata.json on the classpath").isNotNull();
            String metadata = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(metadata).contains("opa.abac.resilience.enabled");
        }
    }

    // --- persistence-conflict advice (5.97, retro-audit fold-in #1) -----------

    private final org.springframework.boot.test.context.runner.WebApplicationContextRunner webRunner =
            new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OpaAbacAutoConfiguration.class));

    @Test // U17 — present by default in a servlet web app; maps BOTH dao conflicts → 409 STATE_CONFLICT
    void persistenceConflictAdvicePresent_andMapsBothConflicts() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(PersistenceConflictProblemAdvice.class);
            PersistenceConflictProblemAdvice advice = context.getBean(PersistenceConflictProblemAdvice.class);

            var optimistic = advice.handlePersistenceConflict(
                    new org.springframework.dao.OptimisticLockingFailureException("row version moved"),
                    new org.springframework.mock.web.MockHttpServletRequest("PUT", "/catalogs/1"));
            assertThat(optimistic.getStatusCode().value()).isEqualTo(409);
            assertThat(optimistic.getBody().errorCode()).isEqualTo("STATE_CONFLICT");

            var integrity = advice.handlePersistenceConflict(
                    new org.springframework.dao.DataIntegrityViolationException("fk violated"),
                    new org.springframework.mock.web.MockHttpServletRequest("DELETE", "/roles/1"));
            assertThat(integrity.getStatusCode().value()).isEqualTo(409);
            assertThat(integrity.getBody().errorCode()).isEqualTo("STATE_CONFLICT");
            // no internals leak: the static detail carries no exception text
            assertThat(integrity.getBody().detail()).doesNotContain("fk violated");
        });
    }

    @Test // U17 — absent when the dao classes are off the classpath
    void persistenceConflictAdviceAbsent_withoutDaoClasses() {
        webRunner.withClassLoader(new FilteredClassLoader(
                        org.springframework.dao.OptimisticLockingFailureException.class))
                .run(context ->
                        assertThat(context).doesNotHaveBean(PersistenceConflictProblemAdvice.class));
    }

    @Test // U17 — absent in a non-web context
    void persistenceConflictAdviceAbsent_withoutWeb() {
        runner.run(context ->
                assertThat(context).doesNotHaveBean(PersistenceConflictProblemAdvice.class));
    }

    @Test // U17 — a user-supplied advice bean overrides the starter's
    void userPersistenceConflictAdviceWins() {
        webRunner.withUserConfiguration(UserAdviceConfig.class).run(context ->
                assertThat(context.getBean(PersistenceConflictProblemAdvice.class))
                        .isSameAs(UserAdviceConfig.ADVICE));
    }

    // --- library not-found advice (retro-audit follow-up) ---------------------

    @Test // U19 — present by default in a servlet web app; maps EntityNotFoundException → 404
    void entityNotFoundAdvicePresent_andMaps404() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(EntityNotFoundProblemAdvice.class);
            EntityNotFoundProblemAdvice advice = context.getBean(EntityNotFoundProblemAdvice.class);

            var response = advice.handleEntityNotFound(
                    new dev.dmitriikonovalov.opaabac.data.service.EntityNotFoundException(
                            "Category not found: 42"),
                    new org.springframework.mock.web.MockHttpServletRequest("PUT", "/categories/42"));
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody().errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
            // no internals leak: the static detail carries no exception text
            assertThat(response.getBody().detail()).doesNotContain("Category not found");
        });
    }

    @Test // U19 — absent when the spring-data module is off the classpath
    void entityNotFoundAdviceAbsent_withoutDataModule() {
        webRunner.withClassLoader(new FilteredClassLoader(
                        dev.dmitriikonovalov.opaabac.data.service.EntityNotFoundException.class))
                .run(context ->
                        assertThat(context).doesNotHaveBean(EntityNotFoundProblemAdvice.class));
    }

    @Test // U19 — absent in a non-web context
    void entityNotFoundAdviceAbsent_withoutWeb() {
        runner.run(context ->
                assertThat(context).doesNotHaveBean(EntityNotFoundProblemAdvice.class));
    }

    @Test // U19 — a user-supplied advice bean overrides the starter's
    void userEntityNotFoundAdviceWins() {
        webRunner.withUserConfiguration(UserNotFoundAdviceConfig.class).run(context ->
                assertThat(context.getBean(EntityNotFoundProblemAdvice.class))
                        .isSameAs(UserNotFoundAdviceConfig.ADVICE));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserNotFoundAdviceConfig {
        static final EntityNotFoundProblemAdvice ADVICE = new EntityNotFoundProblemAdvice();

        @Bean
        EntityNotFoundProblemAdvice entityNotFoundProblemAdvice() {
            return ADVICE;
        }
    }

    // --- action-enrichment advice + write-through wiring (Phase 6, T4) --------

    @Test // U11 — defaults, web app, a resolver present (→ a cache bean) → the advice bean is registered
    void actionEnrichmentAdvicePresent_byDefault() {
        webRunner.withUserConfiguration(ResolverConfig.class).run(context -> {
            assertThat(context).hasSingleBean(
                    dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice.class);
            // the cache bean the advice (and the write-through) feed from exists
            assertThat(context).hasSingleBean(dev.dmitriikonovalov.opaabac.core.AbacResourceCache.class);
        });
    }

    @Test // U12 — opa.abac.action-enrichment.enabled=false → NO advice bean (the byte-identical rollback)
    void actionEnrichmentAdviceAbsent_whenDisabled() {
        webRunner.withPropertyValues("opa.abac.action-enrichment.enabled=false")
                .withUserConfiguration(ResolverConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean(
                        dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice.class));
    }

    @Test // U11/U12 — the AbacQueryService receives the cache collaborator only when enrichment is enabled
    // (proven via the write-through behavior: a list query caches its survivors iff the collaborator is wired)
    void writeThroughCollaborator_wiredOnlyWhenEnabled() {
        // enabled (default): a findAuthorized over a coarse-allow path write-throughs the survivor.
        webRunner.withUserConfiguration(ResolverConfig.class, RecordingCacheConfig.class,
                        AllowAllOpaClientConfig.class)
                .withPropertyValues("opa.abac.partial-eval.enabled=false") // coarse path, no compile
                .run(context -> {
                    RecordingCacheConfig.RecordingCache cache = context.getBean(RecordingCacheConfig.RecordingCache.class);
                    runListQuery(context);
                    assertThat(cache.puts).as("enrichment enabled → write-through populates the cache").isNotEmpty();
                });
        // disabled: the same query caches nothing (the collaborator was not wired).
        webRunner.withUserConfiguration(ResolverConfig.class, RecordingCacheConfig.class,
                        AllowAllOpaClientConfig.class)
                .withPropertyValues("opa.abac.action-enrichment.enabled=false",
                        "opa.abac.partial-eval.enabled=false")
                .run(context -> {
                    RecordingCacheConfig.RecordingCache cache = context.getBean(RecordingCacheConfig.RecordingCache.class);
                    runListQuery(context);
                    assertThat(cache.puts).as("enrichment disabled → write-through dormant").isEmpty();
                });
    }

    @Test // U13 — non-web context → no advice bean
    void actionEnrichmentAdviceAbsent_withoutWeb() {
        runner.withUserConfiguration(ResolverConfig.class).run(context ->
                assertThat(context).doesNotHaveBean(
                        dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice.class));
    }

    @Test // U12 — a resolver present but resource-resolution OFF → no cache bean → the advice must NOT
    // activate (the regression that broke the resolution-off IT suites: a @Component resolver exists while
    // resolution is disabled, so gating on the resolver alone would try to wire a missing AbacResourceCache)
    void actionEnrichmentAdviceAbsent_whenResolutionDisabled() {
        webRunner.withPropertyValues("opa.abac.resource-resolution.enabled=false")
                .withUserConfiguration(ResolverConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed(); // the context must START (no missing-cache wiring error)
                    assertThat(context).doesNotHaveBean(
                            dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice.class);
                });
    }

    @Test // U13 — a user-supplied advice bean overrides the starter's (@ConditionalOnMissingBean)
    void userActionEnrichmentAdviceWins() {
        webRunner.withUserConfiguration(ResolverConfig.class, UserEnrichmentAdviceConfig.class).run(context ->
                assertThat(context.getBean(
                        dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice.class))
                        .isSameAs(UserEnrichmentAdviceConfig.ADVICE));
    }

    @Test // U13 — the configuration metadata carries the new property (default true)
    void configurationMetadataCarriesActionEnrichmentProperty() throws Exception {
        try (java.io.InputStream in = getClass()
                .getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("spring-configuration-metadata.json on the classpath").isNotNull();
            String metadata = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(metadata).contains("opa.abac.action-enrichment.enabled");
        }
    }

    /** Run a list query against the wired AbacQueryService with a hand-rolled repo returning one row. */
    private static void runListQuery(
            org.springframework.context.ApplicationContext context) {
        var service = context.getBean(dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService.class);
        service.findAuthorized(new OneRowRepo(), null, new dev.dmitriikonovalov.opaabac.core.AbacContext(
                new dev.dmitriikonovalov.opaabac.core.AbacContext.Subject("u", java.util.List.of(), java.util.Map.of()),
                "category:view",
                new dev.dmitriikonovalov.opaabac.core.AbacContext.Resource("category", null, java.util.Map.of()),
                java.util.Map.of()));
    }

    /**
     * A minimal {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor} returning one
     * survivor row for any {@code findAll(spec)} — enough to exercise the write-through wiring without
     * Mockito (the starter tests stay mock-free, using real {@code ApplicationContextRunner} configs).
     */
    static final class OneRowRepo
            implements org.springframework.data.jpa.repository.JpaSpecificationExecutor<EnrichmentRow> {
        @Override
        public java.util.List<EnrichmentRow> findAll(
                org.springframework.data.jpa.domain.Specification<EnrichmentRow> spec) {
            return java.util.List.of(new EnrichmentRow("r-1"));
        }

        @Override
        public Optional<EnrichmentRow> findOne(
                org.springframework.data.jpa.domain.Specification<EnrichmentRow> spec) {
            return Optional.empty();
        }

        @Override
        public java.util.List<EnrichmentRow> findAll(
                org.springframework.data.jpa.domain.Specification<EnrichmentRow> spec,
                org.springframework.data.domain.Sort sort) {
            return java.util.List.of(new EnrichmentRow("r-1"));
        }

        @Override
        public org.springframework.data.domain.Page<EnrichmentRow> findAll(
                org.springframework.data.jpa.domain.Specification<EnrichmentRow> spec,
                org.springframework.data.domain.Pageable pageable) {
            return new org.springframework.data.domain.PageImpl<>(
                    java.util.List.of(new EnrichmentRow("r-1")), pageable, 1);
        }

        @Override
        public long count(org.springframework.data.jpa.domain.Specification<EnrichmentRow> spec) {
            return 1;
        }

        @Override
        public boolean exists(org.springframework.data.jpa.domain.Specification<EnrichmentRow> spec) {
            return true;
        }

        @Override
        public long delete(org.springframework.data.jpa.domain.Specification<EnrichmentRow> spec) {
            return 0;
        }

        @Override
        public <S extends EnrichmentRow, R> R findBy(
                org.springframework.data.jpa.domain.Specification<EnrichmentRow> spec,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery
                        .FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserEnrichmentAdviceConfig {
        static final dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice ADVICE =
                new dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice(
                        new NoOpOpaClient(),
                        new dev.dmitriikonovalov.opaabac.security.RequestAttributesResourceCache(),
                        (u, t, i) -> Optional.empty(),
                        null);

        @Bean
        dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice actionEnrichmentAdvice() {
            return ADVICE;
        }
    }

    /** A do-nothing OpaClient for constructing a user-supplied advice override. */
    static final class NoOpOpaClient implements dev.dmitriikonovalov.opaabac.core.OpaClient {
        @Override
        public boolean allow(dev.dmitriikonovalov.opaabac.core.AbacContext c) {
            return false;
        }

        @Override
        public dev.dmitriikonovalov.opaabac.core.PartialResult compile(
                dev.dmitriikonovalov.opaabac.core.AbacContext c) {
            return dev.dmitriikonovalov.opaabac.core.PartialResult.denyAll();
        }

        @Override
        public java.util.List<Boolean> allowAll(
                java.util.List<dev.dmitriikonovalov.opaabac.core.AbacContext> contexts) {
            return java.util.Collections.nCopies(contexts.size(), false);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingCacheConfig {
        @Bean
        RecordingCache abacResourceCache() {
            return new RecordingCache();
        }

        static final class RecordingCache implements dev.dmitriikonovalov.opaabac.core.AbacResourceCache {
            final java.util.List<String> puts = new java.util.ArrayList<>();

            @Override
            public <T> Optional<T> get(String type, String id, Class<T> as) {
                return Optional.empty();
            }

            @Override
            public void put(String type, String id, Object resource) {
                puts.add(type + ":" + id);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AllowAllOpaClientConfig {
        @Bean
        dev.dmitriikonovalov.opaabac.core.OpaClient opaClient() {
            return new dev.dmitriikonovalov.opaabac.core.OpaClient() {
                @Override
                public boolean allow(dev.dmitriikonovalov.opaabac.core.AbacContext c) {
                    return true; // coarse-allow path: the list returns its survivors
                }

                @Override
                public dev.dmitriikonovalov.opaabac.core.PartialResult compile(
                        dev.dmitriikonovalov.opaabac.core.AbacContext c) {
                    return dev.dmitriikonovalov.opaabac.core.PartialResult.allowAll();
                }

                @Override
                public java.util.List<Boolean> allowAll(
                        java.util.List<dev.dmitriikonovalov.opaabac.core.AbacContext> contexts) {
                    return java.util.Collections.nCopies(contexts.size(), true);
                }
            };
        }
    }

    record EnrichmentRow(String id) implements dev.dmitriikonovalov.opaabac.core.AbacDataObject {
        @Override
        public String abacResourceType() {
            return "category";
        }

        @Override
        public String abacResourceId() {
            return id;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ResolverConfig {
        static final dev.dmitriikonovalov.opaabac.core.AbacResourceResolver RESOLVER =
                (type, id) -> Optional.empty();

        @Bean
        dev.dmitriikonovalov.opaabac.core.AbacResourceResolver abacResourceResolver() {
            return RESOLVER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserCacheConfig {
        static final dev.dmitriikonovalov.opaabac.core.AbacResourceCache CACHE =
                new dev.dmitriikonovalov.opaabac.security.RequestAttributesResourceCache();

        @Bean
        dev.dmitriikonovalov.opaabac.core.AbacResourceCache abacResourceCache() {
            return CACHE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserAdviceConfig {
        static final PersistenceConflictProblemAdvice ADVICE = new PersistenceConflictProblemAdvice();

        @Bean
        PersistenceConflictProblemAdvice persistenceConflictProblemAdvice() {
            return ADVICE;
        }
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
