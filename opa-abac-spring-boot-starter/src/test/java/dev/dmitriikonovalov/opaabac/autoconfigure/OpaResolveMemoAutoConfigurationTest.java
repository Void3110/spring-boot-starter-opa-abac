package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreePathSource;
import dev.dmitriikonovalov.opaabac.security.MemoizingRoleDefinitionSupplier;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link OpaResolveMemoAutoConfiguration} wiring tests (ADR 0023; QA case U5): the flag's
 * default-on / off states, bean-level wrapping of the app's own beans (the load-bearing property —
 * direct injectors share the memo), the double-wrap guard, and the classpath back-offs.
 */
class OpaResolveMemoAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpaAbacAutoConfiguration.class));

    @Test // U5 — flag default (on): the supplier bean is memo-wrapped
    void supplierWrapped_byDefault() {
        runner.run(context -> assertThat(context.getBean(RoleDefinitionSupplier.class))
                .isInstanceOf(MemoizingRoleDefinitionSupplier.class));
    }

    @Test // U5 — a USER-supplied supplier bean is wrapped too (bean-level, not injection-point)
    void userSupplierWrapped() {
        runner.withUserConfiguration(UserSupplierConfig.class)
                .run(context -> assertThat(context.getBean(RoleDefinitionSupplier.class))
                        .isInstanceOf(MemoizingRoleDefinitionSupplier.class));
    }

    @Test // U5 — flag off: the raw app bean, unwrapped
    void supplierUnwrapped_whenFlagOff() {
        runner.withPropertyValues("opa.abac.resolve-memo.enabled=false")
                .withUserConfiguration(UserSupplierConfig.class)
                .run(context -> {
                    assertThat(context.getBean(RoleDefinitionSupplier.class))
                            .isNotInstanceOf(MemoizingRoleDefinitionSupplier.class);
                    assertThat(context.getBean(RoleDefinitionSupplier.class))
                            .isSameAs(UserSupplierConfig.SUPPLIER);
                });
    }

    @Test // U5 — the ancestor resolver bean is memo-wrapped under the SAME flag (one knob, one axis)
    void ancestorResolverWrapped_byDefault() {
        runner.withPropertyValues("opa.abac.hierarchy.enabled=true")
                .withUserConfiguration(LtreeSourceConfig.class)
                .run(context -> assertThat(context.getBean(AncestorResolver.class))
                        .isInstanceOf(MemoizingAncestorResolver.class));
    }

    @Test // U5 — flag off unwraps BOTH memos with the beans untouched
    void ancestorResolverUnwrapped_whenFlagOff() {
        runner.withPropertyValues(
                        "opa.abac.hierarchy.enabled=true",
                        "opa.abac.resolve-memo.enabled=false")
                .withUserConfiguration(LtreeSourceConfig.class)
                .run(context -> assertThat(context.getBean(AncestorResolver.class))
                        .isNotInstanceOf(MemoizingAncestorResolver.class));
    }

    @Test // U5 — no double-wrap: re-processing an already-wrapped bean returns it unchanged
    void noDoubleWrap() {
        runner.run(context -> {
            RoleDefinitionSupplier wrapped = context.getBean(RoleDefinitionSupplier.class);
            assertThat(wrapped).isInstanceOf(MemoizingRoleDefinitionSupplier.class);
            BeanPostProcessor decorator = context.getBean(
                    "memoizingRoleDefinitionSupplierDecorator", BeanPostProcessor.class);
            assertThat(decorator.postProcessAfterInitialization(wrapped, "roleDefinitionSupplier"))
                    .isSameAs(wrapped);
        });
    }

    @Test // U5 — spring-web absent → the whole memo config backs off (nothing to scope a memo to)
    void backsOff_withoutSpringWeb() {
        // Hiding RequestContextHolder also hides the web-only starter groups; the core supplier
        // bean must come up RAW — no memo BPP ran.
        runner.withClassLoader(new FilteredClassLoader(
                        org.springframework.web.context.request.RequestContextHolder.class,
                        org.springframework.security.web.SecurityFilterChain.class,
                        org.springframework.web.filter.OncePerRequestFilter.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RoleDefinitionSupplier.class))
                            .isNotInstanceOf(MemoizingRoleDefinitionSupplier.class);
                });
    }

    @Test // U5 — Spring Data JPA absent → the role memo still wires; only the ancestor half backs off
    void ancestorHalfBacksOff_withoutJpa() {
        runner.withClassLoader(new FilteredClassLoader(
                        org.springframework.data.jpa.repository.JpaSpecificationExecutor.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RoleDefinitionSupplier.class))
                            .isInstanceOf(MemoizingRoleDefinitionSupplier.class);
                    assertThat(context).doesNotHaveBean("memoizingAncestorResolverDecorator");
                });
    }

    @Test // the generated configuration metadata carries the new property
    void configurationMetadataCarriesResolveMemoProperty() throws Exception {
        try (java.io.InputStream in = getClass()
                .getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("spring-configuration-metadata.json on the classpath").isNotNull();
            String metadata = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(metadata).contains("opa.abac.resolve-memo.enabled");
        }
    }

    @Test // property binding: the flag lands in the bound properties (default true)
    void resolveMemoPropertyBinds() {
        runner.run(context -> assertThat(
                context.getBean(OpaAbacProperties.class).getResolveMemo().isEnabled()).isTrue());
        runner.withPropertyValues("opa.abac.resolve-memo.enabled=false")
                .run(context -> assertThat(
                        context.getBean(OpaAbacProperties.class).getResolveMemo().isEnabled()).isFalse());
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSupplierConfig {

        static final RoleDefinitionSupplier SUPPLIER =
                (userId, type, id) -> Optional.of(new RoleDefinition("user-role", Map.of(), Map.of()));

        @Bean
        RoleDefinitionSupplier userRoleDefinitionSupplier() {
            return SUPPLIER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LtreeSourceConfig {

        @Bean
        LtreePathSource ltreePathSource() {
            return (type, id) -> Optional.empty();
        }
    }
}
