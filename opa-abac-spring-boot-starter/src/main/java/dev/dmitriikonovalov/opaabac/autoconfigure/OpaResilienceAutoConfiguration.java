package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.security.resilience.CallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.Resilience4jCallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.ResilienceConfig;
import dev.dmitriikonovalov.opaabac.security.resilience.ResilientOpaClient;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationCondition;

/**
 * Wraps the starter's plain {@link OpaClient} with a {@link ResilientOpaClient} (Slice B3, ADR 0017 §1/§6)
 * — <strong>only</strong> when Resilience4j is on the classpath and B3 resilience is enabled. The standard
 * Spring Boot "optional integration" pattern: an adopter who adds R4j (and leaves the defaults) gets
 * retry/breaker on OPA calls; an adopter who does not — or who sets {@code resilience.enabled=false} or
 * {@code resilience.opa.enabled=false} — gets today's plain {@code HttpOpaClient}, byte-identical to pre-B3.
 *
 * <h2>Why {@code @ConditionalOnClass} on R4j's {@code CircuitBreaker}</h2>
 * R4j is an <em>optional</em> starter dependency (declared {@code compileOnly} on the starter). The condition
 * keys off a real R4j type, so when the adopter has not added R4j the whole config silently backs off and
 * the plain client is used — the lean-starter promise. {@code @ConditionalOnProperty} is not repeatable on a
 * type, so the master + per-edge switches are an {@link ResilienceEnabled} {@code AllNestedConditions}.
 *
 * <h2>Why a {@link BeanPostProcessor}, not a second {@code @Bean OpaClient}</h2>
 * The plain {@code OpaClient} bean is {@code @ConditionalOnMissingBean}, and a custom adopter bean must keep
 * winning. A post-processor <em>decorates</em> whatever {@code OpaClient} the context ended up with (the
 * starter's {@code HttpOpaClient}, or a user-supplied client) without competing for the bean name — the
 * resilient wrapper is transparent to every injection point. The OPA-edge {@link CallGuard} is built lazily,
 * inside the post-processor, from {@code opa.abac.resilience.opa.*} (master switch folded in) — so it is not
 * a context bean racing the post-processor's own early initialization.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CircuitBreaker")
@Conditional(OpaResilienceAutoConfiguration.ResilienceEnabled.class)
class OpaResilienceAutoConfiguration {

    /**
     * Decorates the context's {@code OpaClient} bean with the resilient wrapper. Runs after the target bean
     * is fully initialized; only the {@code OpaClient} bean is touched, every other bean passes through. The
     * guard is constructed once, on first wrap, from the bound {@link OpaAbacProperties}.
     */
    @Bean
    static BeanPostProcessor resilientOpaClientDecorator(
            org.springframework.beans.factory.ObjectProvider<OpaAbacProperties> properties) {
        return new BeanPostProcessor() {
            private volatile CallGuard guard;

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof OpaClient delegate && !(bean instanceof ResilientOpaClient)) {
                    return new ResilientOpaClient(delegate, guard(properties.getObject()));
                }
                return bean;
            }

            private CallGuard guard(OpaAbacProperties props) {
                CallGuard local = guard;
                if (local == null) {
                    OpaAbacProperties.Resilience r = props.getResilience();
                    ResilienceConfig config = r.getOpa().toConfig(r.isEnabled());
                    local = new Resilience4jCallGuard("opa", config);
                    guard = local;
                }
                return local;
            }
        };
    }

    /**
     * Active iff <em>both</em> {@code opa.abac.resilience.enabled} (master) and
     * {@code opa.abac.resilience.opa.enabled} (the OPA edge) are on — both default on. An
     * {@link AllNestedConditions} because {@code @ConditionalOnProperty} is not repeatable on one type
     * (mirrors {@code ActionEnrichmentAutoConfiguration.EnrichmentActive}).
     */
    static final class ResilienceEnabled extends AllNestedConditions {

        ResilienceEnabled() {
            super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "opa.abac.resilience", name = "enabled",
                havingValue = "true", matchIfMissing = true)
        static class MasterEnabled {}

        @ConditionalOnProperty(prefix = "opa.abac.resilience.opa", name = "enabled",
                havingValue = "true", matchIfMissing = true)
        static class OpaEdgeEnabled {}
    }
}
