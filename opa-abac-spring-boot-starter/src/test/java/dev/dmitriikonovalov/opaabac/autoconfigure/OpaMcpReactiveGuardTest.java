package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.ApplicationContextAssertProvider;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * U24 (MCP-AUTH-LIBRARY T5): a reactive (Spring WebFlux) web application fails startup — WebFlux is out
 * of scope for v1 (ADR 0036 §4) — while the same configuration on a servlet context starts.
 *
 * <h2>The classpath prerequisite</h2>
 * Boot's {@code @ConditionalOnWebApplication(type = REACTIVE)} matches only when
 * {@code org.springframework.web.reactive.HandlerResult} ({@code spring-webflux}) is loadable, so the
 * reactive half needs {@code spring-webflux} on the test classpath. The starter's build adds it on CI and
 * with {@code -PwithWebflux} (see its build file): a host whose Gradle cannot fetch new artifacts still
 * builds offline, and this test reports itself <em>skipped</em> there — never silently green. On CI a
 * missing artifact is a <strong>failure</strong>, not a skip, so the proof cannot quietly disappear.
 */
class OpaMcpReactiveGuardTest {

    private static final String WEBFLUX_MARKER = "org.springframework.web.reactive.HandlerResult";

    private static final AtomicInteger PROBE = new AtomicInteger();

    @BeforeEach
    void resetProbe() {
        PROBE.set(0);
    }

    private static void requireWebflux() {
        boolean present = ClassUtils.isPresent(WEBFLUX_MARKER, null);
        if (System.getenv("CI") != null) {
            assertThat(present)
                    .as("spring-webflux must be on the starter's test classpath on CI — U24 cannot be skipped there")
                    .isTrue();
        }
        Assumptions.assumeTrue(present,
                "spring-webflux is not on the test classpath (an offline host) — run with -PwithWebflux");
    }

    private static final String[] BASELINE_PROPERTIES = {
        "opa.abac.subject.attribute-claims=act_chain", "opa.abac.base-url=http://127.0.0.1:1"
    };

    /** The complete baseline (the QA preamble), so the reactive check is the only one that can fail. */
    private static ReactiveWebApplicationContextRunner reactiveBaseline() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpaAbacAutoConfiguration.class, OpaMcpAutoConfiguration.class))
                .withPropertyValues(BASELINE_PROPERTIES)
                .withUserConfiguration(Prerequisites.class);
    }

    /** The same configuration on a servlet context. */
    private static WebApplicationContextRunner servletBaseline() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpaAbacAutoConfiguration.class, OpaMcpAutoConfiguration.class))
                .withPropertyValues(BASELINE_PROPERTIES)
                .withUserConfiguration(Prerequisites.class);
    }

    private static String failureMessages(ApplicationContextAssertProvider<?> context) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Test // U24 — a reactive web application fails, naming WebFlux and "not supported in v1"
    void aReactiveWebApplicationFailsStartup() {
        requireWebflux();
        reactiveBaseline().run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context))
                    .contains("reactive (Spring WebFlux)")
                    .contains("not supported in v1");
        });
    }

    @Test // U24 — the same, under real lazy initialization (the QA preamble's processor installed)
    void aReactiveWebApplicationFailsStartupUnderLazyInitialization() {
        requireWebflux();
        reactiveBaseline()
                .withInitializer(c -> c.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withUserConfiguration(LazyProbe.class)
                .run(context -> {
                    assertThat(PROBE).as("the fixture really is lazy").hasValue(0);
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context)).contains("not supported in v1");
                });
    }

    @Test // U24 — the servlet runner with the same configuration starts (no WebFlux artifact needed)
    void theSameConfigurationOnAServletContextStarts() {
        servletBaseline().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(McpStartupVerifier.ReactiveWebApplication.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Prerequisites {
        @Bean
        PrincipalCeilingSupplier ceilingSource() {
            return (subject, type) -> Optional.empty();
        }

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyProbe {
        @Bean
        Object lazyProbe() {
            PROBE.incrementAndGet();
            return new Object();
        }
    }
}
