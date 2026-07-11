package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import dev.dmitriikonovalov.opaabac.security.MemoizingRoleDefinitionSupplier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the context's resolution beans with the Slice-7.3 request-scoped memo decorators
 * (ADR 0023): the {@link RoleDefinitionSupplier} bean with {@link MemoizingRoleDefinitionSupplier}
 * and — when Spring Data JPA is present — the {@link AncestorResolver} bean with
 * {@link MemoizingAncestorResolver}. One flag governs both: {@code opa.abac.resolve-memo.enabled}
 * (default on); off restores per-call resolution semantics with the beans untouched.
 *
 * <h2>Why {@link BeanPostProcessor}s, not wrapper beans</h2>
 * The {@code resilientOpaClientDecorator} precedent: every resolution bean is
 * {@code @ConditionalOnMissingBean}-overridable, and a custom adopter bean must keep winning. The
 * post-processors decorate whatever bean the context ended up with, so the memo is transparent to
 * every injection point — <strong>including app-side consumers that inject the supplier
 * directly</strong> (the example's list authorizers) and the starter's own method-reference
 * bindings ({@code AncestorChainSupplier} → {@code hierarchyResolver::ancestorsOf}), which capture
 * the post-processed singleton. Bean-level wrapping is load-bearing: an injection-point wrapper
 * would leave direct consumers on the raw bean and split the request's answer per call site.
 *
 * <p>Decoration composes with B3: the resolve-edge {@code CallGuard} lives <em>inside</em> the
 * app's HTTP supplier, so the order is memo(supplier(guard)) — a memo hit never touches the guard
 * and the breaker samples at most one real call per key per request.
 *
 * <p>Guarded on {@code RequestContextHolder} (spring-web): without a web request there is nothing
 * to scope a memo to — the decorators themselves also degrade to pure pass-through per call. The
 * ancestor half is additionally guarded on Spring Data JPA (the {@code AncestorResolver} SPI's
 * module), mirroring {@code HierarchyAutoConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.context.request.RequestContextHolder")
@ConditionalOnProperty(prefix = "opa.abac.resolve-memo", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class OpaResolveMemoAutoConfiguration {

    /**
     * Decorates the context's {@code RoleDefinitionSupplier} bean. Runs after the target bean is
     * fully initialized; the {@code instanceof} guard skips double-wrapping on any re-processing.
     * {@code static}: a {@code BeanPostProcessor} {@code @Bean} method must not force early
     * initialization of its enclosing configuration.
     */
    @Bean
    static BeanPostProcessor memoizingRoleDefinitionSupplierDecorator() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RoleDefinitionSupplier delegate
                        && !(bean instanceof MemoizingRoleDefinitionSupplier)) {
                    return new MemoizingRoleDefinitionSupplier(delegate);
                }
                return bean;
            }
        };
    }

    /**
     * The ancestor half, present only with Spring Data JPA on the classpath (the
     * {@link AncestorResolver} SPI lives in {@code opa-abac-spring-data} and references JPA types).
     * Covers the query path and the enrichment path through one decorator — both bind to this bean.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaSpecificationExecutor")
    static class AncestorMemoConfiguration {

        @Bean
        static BeanPostProcessor memoizingAncestorResolverDecorator() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof AncestorResolver delegate
                            && !(bean instanceof MemoizingAncestorResolver)) {
                        return new MemoizingAncestorResolver(delegate);
                    }
                    return bean;
                }
            };
        }
    }
}
