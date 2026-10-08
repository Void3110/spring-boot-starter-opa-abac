package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR 0038 — {@code opa.abac.enabled=false} with an {@code @OpaPreAuthorize} method in the context. Until
 * 1.5.0 that method ran with no decision at all; now the context refuses to start unless
 * {@code opa.abac.allow-ungated-methods=true} acknowledges it.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpaAbacDisabledAutoConfigurationTest {

    private static final String WARNING = "@OpaPreAuthorize method(s) run with NO authorization decision";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpaAbacAutoConfiguration.class, OpaAbacDisabledAutoConfiguration.class));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test // the default (no property): the starter is on, the gate decides — no subject, so it denies
    void enabledByDefault_gateDecides_noGuard() {
        runner.withUserConfiguration(MethodSecurity.class, GatedServiceConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(UngatedMethodsGuard.class);
            GatedService service = context.getBean(GatedService.class);
            assertThatThrownBy(service::write).isInstanceOf(AuthorizationDeniedException.class);
        });
    }

    @Test // the finding, closed: off + a declared gate + no acknowledgment → startup fails, naming the method
    void disabled_withAGatedMethod_failsStartup() {
        runner.withUserConfiguration(MethodSecurity.class, GatedServiceConfig.class)
                .withPropertyValues("opa.abac.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("1 method(s) that declare an authorization gate")
                            .hasMessageContaining("OpaAbacDisabledAutoConfigurationTest.GatedService#write")
                            .hasMessageContaining("opa.abac.allow-ungated-methods=true");
                });
    }

    @Test // acknowledged: the context starts, the method runs with no decision (what was accepted), a WARN names it
    void disabled_acknowledged_startsUngatedAndWarns(CapturedOutput output) {
        runner.withUserConfiguration(MethodSecurity.class, GatedServiceConfig.class)
                .withPropertyValues("opa.abac.enabled=false", "opa.abac.allow-ungated-methods=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GatedService.class).write()).isEqualTo("written");
                    assertThat(output).contains(WARNING).contains("GatedService#write");
                });
    }

    @Test // the rig's form (deploy.sh): both switches as environment variables, the dashed name underscored
    void disabled_acknowledgedThroughEnvironmentVariables_starts(CapturedOutput output) {
        runner.withUserConfiguration(MethodSecurity.class, GatedServiceConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("rig-" + SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                                "OPA_ABAC_ENABLED", "false",
                                "OPA_ABAC_ALLOW_UNGATED_METHODS", "true"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output).contains(WARNING);
                });
    }

    @Test // nothing declared, nothing to guard: off needs no acknowledgment and logs nothing
    void disabled_withoutGatedMethods_startsQuietly(CapturedOutput output) {
        runner.withUserConfiguration(MethodSecurity.class, PlainServiceConfig.class)
                .withPropertyValues("opa.abac.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UngatedMethodsGuard.class);
                    assertThat(output).doesNotContain(WARNING);
                });
    }

    @Test // the annotation declares the gate; the guard does not lean on @EnableMethodSecurity being present
    void disabled_withoutMethodSecurity_stillFails() {
        runner.withUserConfiguration(GatedServiceConfig.class)
                .withPropertyValues("opa.abac.enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test // the advisor's pointcut checks inherited declarations, so the guard does too
    void disabled_gateDeclaredOnTheInterface_fails() {
        runner.withUserConfiguration(InterfaceGatedConfig.class)
                .withPropertyValues("opa.abac.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("InterfaceGatedImpl#write");
                });
    }

    @Test // a JDK-proxied bean is judged by its target class: the gate sits on the implementation only
    void disabled_proxiedBean_judgedByItsTarget(CapturedOutput output) {
        runner.withUserConfiguration(MethodSecurity.class, ProxiedConfig.class)
                .withPropertyValues("opa.abac.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("ProxiedImpl#write");
                });
        // the same context, acknowledged: proof the bean really is a JDK proxy (the @PreAuthorize on read()
        // made method security proxy it), which hides the implementation's annotation from its own class
        runner.withUserConfiguration(MethodSecurity.class, ProxiedConfig.class)
                .withPropertyValues("opa.abac.enabled=false", "opa.abac.allow-ungated-methods=true")
                .run(context -> {
                    assertThat(AopUtils.isJdkDynamicProxy(context.getBean(Proxied.class))).isTrue();
                    assertThat(output).contains("ProxiedImpl#write");
                });
    }

    @Test // a lazy bean is never instantiated at startup; it is judged by its predicted type
    void disabled_lazyBean_fails() {
        runner.withUserConfiguration(LazyGatedConfig.class)
                .withPropertyValues("opa.abac.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("GatedService#write");
                });
    }

    @Test // no Spring Security + web: the advisor never exists, enabled or not — there is nothing to lose
    void disabled_withoutSecurityClasses_noGuard() {
        runner.withClassLoader(new FilteredClassLoader(SecurityFilterChain.class, OncePerRequestFilter.class))
                .withUserConfiguration(GatedServiceConfig.class)
                .withPropertyValues("opa.abac.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(UngatedMethodsGuard.class);
                });
    }

    @Test // the acknowledgment never weakens an enabled starter (the rig sets it next to a flippable switch)
    void enabled_acknowledgmentHasNoEffect() {
        runner.withUserConfiguration(MethodSecurity.class, GatedServiceConfig.class)
                .withPropertyValues("opa.abac.enabled=true", "opa.abac.allow-ungated-methods=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(UngatedMethodsGuard.class);
                    GatedService service = context.getBean(GatedService.class);
                    assertThatThrownBy(service::write).isInstanceOf(AuthorizationDeniedException.class);
                });
    }

    @Test // the generated configuration metadata documents the acknowledgment
    void configurationMetadataCarriesTheAcknowledgment() throws Exception {
        try (java.io.InputStream in = getClass()
                .getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("spring-configuration-metadata.json on the classpath").isNotNull();
            String metadata = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(metadata).contains("opa.abac.allow-ungated-methods");
        }
    }

    @EnableMethodSecurity
    @Configuration(proxyBeanMethods = false)
    static class MethodSecurity {}

    @Configuration(proxyBeanMethods = false)
    static class GatedServiceConfig {

        @Bean
        GatedService gatedService() {
            return new GatedService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyGatedConfig {

        @Bean
        @Lazy
        GatedService lazyGatedService() {
            return new GatedService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PlainServiceConfig {

        @Bean
        PlainService plainService() {
            return new PlainService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InterfaceGatedConfig {

        @Bean
        InterfaceGatedImpl interfaceGated() {
            return new InterfaceGatedImpl();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxiedConfig {

        @Bean
        Proxied proxied() {
            return new ProxiedImpl();
        }
    }

    static class GatedService {

        @OpaPreAuthorize(action = "product:write", resourceType = "'product'")
        public String write() {
            return "written";
        }
    }

    static class PlainService {

        public String write() {
            return "written";
        }
    }

    interface InterfaceGated {

        @OpaPreAuthorize(action = "product:write", resourceType = "'product'")
        String write();
    }

    static class InterfaceGatedImpl implements InterfaceGated {

        @Override
        public String write() {
            return "written";
        }
    }

    interface Proxied {

        String read();

        String write();
    }

    static class ProxiedImpl implements Proxied {

        @Override
        @PreAuthorize("permitAll()")
        public String read() {
            return "read";
        }

        @Override
        @OpaPreAuthorize(action = "product:write", resourceType = "'product'")
        public String write() {
            return "written";
        }
    }
}
