package dev.dmitriikonovalov.opaabac.security;

import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.util.function.SingletonSupplier;

/**
 * Registers {@link OpaPreAuthorizeAuthorizationManager} as a before-method security interceptor bound
 * to {@link OpaPreAuthorize}.
 *
 * <p>Requires {@code @EnableMethodSecurity} on the application. The advisor is ordered just before
 * Spring Security's own {@code @PreAuthorize} interceptor so an OPA deny short-circuits early.
 *
 * <p><strong>The manager is resolved lazily</strong> — an {@link ObjectProvider} drained on the first
 * decision and cached, never injected into the advisor directly. The advisor is
 * {@code ROLE_INFRASTRUCTURE}, so it is instantiated <em>during</em> {@code BeanPostProcessor}
 * registration (the AOP advisor-discovery window). A direct injection would drag the manager and its
 * whole collaborator graph ({@code RoleDefinitionSupplier}, {@code OpaClient},
 * {@code ResourceResolutionSupport}, …) into existence inside that window, where user-declared
 * {@code BeanPostProcessor}s are not yet registered — the <em>gate's</em> references would silently
 * skip every decorator the starter applies bean-level (the B3 resilience wrap, ADR 0017; the
 * ADR 0023 request-memo wrap) while every later consumer got the decorated bean: one request, two
 * supplier identities. Deferral moves the manager's creation to the first authorization decision
 * (long after context refresh), so the gate shares the same decorated beans as every other consumer —
 * the same deferral idiom Spring Security's own method-security configuration applies to its
 * interceptor managers.
 */
@Configuration(proxyBeanMethods = false)
public class OpaMethodSecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpaMethodSecurityConfiguration.class);

    /** Run just ahead of {@code @PreAuthorize} (whose interceptor order is 200). */
    static final int INTERCEPTOR_ORDER = 190;

    /**
     * The marker {@code @Configuration} Spring Security registers <em>only</em> when
     * {@code @EnableMethodSecurity} is present (imported by its {@code MethodSecuritySelector}). Its
     * presence is our reliable "method security is on" probe — checking for the interceptor bean type
     * would be circular, since this starter registers one of that type regardless.
     */
    private static final String METHOD_SECURITY_MARKER =
            "org.springframework.security.config.annotation.method.configuration."
                    + "PrePostMethodSecurityConfiguration";

    /**
     * Fail-LOUD-not-open guard for the single silent misconfiguration this starter cannot prevent by
     * construction: the {@code @OpaPreAuthorize} advisor is registered here, but the annotations do
     * nothing unless the application enables Spring method security. A starter must not call
     * {@code @EnableMethodSecurity} itself (that is the app's decision — interceptor ordering, the
     * pre/post modes, its other method-security). So if an adopter annotates methods with
     * {@code @OpaPreAuthorize} but forgets {@code @EnableMethodSecurity}, the ABAC method gates are
     * silently NOT enforced — no error, no log. This bean converts that silent no-op into a loud
     * startup WARNING (it never fails the context: coarse access is still governed by the app's
     * {@code SecurityFilterChain}, and a hard failure would be surprising for an app that deliberately
     * uses only the request-level {@code OpaAuthorizationManager}). See the 7.4 zero-config audit (F1).
     */
    @Bean
    SmartInitializingSingleton opaMethodSecurityEnablementCheck(ApplicationContext context) {
        return () -> {
            boolean methodSecurityEnabled = isBeanTypePresent(context, METHOD_SECURITY_MARKER);
            if (!methodSecurityEnabled) {
                log.warn("@OpaPreAuthorize support is registered but Spring method security is NOT enabled "
                        + "(no @EnableMethodSecurity on the application). Every @OpaPreAuthorize method gate "
                        + "is therefore SILENTLY IGNORED — the fine-grained ABAC decision does not run. Add "
                        + "@EnableMethodSecurity to a @Configuration class to activate the gates. (If you use "
                        + "only the request-level OpaAuthorizationManager and not method security, this "
                        + "warning is expected and can be ignored.)");
            }
        };
    }

    private static boolean isBeanTypePresent(ApplicationContext context, String className) {
        try {
            Class<?> type = Class.forName(className, false, context.getClassLoader());
            return context.getBeanNamesForType(type, false, false).length > 0;
        } catch (ClassNotFoundException _) {
            // Spring Security's method-security config type isn't even on the classpath -> method
            // security cannot be enabled -> the warning applies.
            return false;
        }
    }

    @Bean
    @Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
    @Order(INTERCEPTOR_ORDER)
    AuthorizationManagerBeforeMethodInterceptor opaPreAuthorizeMethodInterceptor(
            ObjectProvider<OpaPreAuthorizeAuthorizationManager> opaPreAuthorizeAuthorizationManager) {

        // checkInherited = true: the annotation is found on the *most specific* method AND up the
        // type hierarchy — so @OpaPreAuthorize declared on an interface method is matched under
        // class-based (CGLIB) proxies too, the same posture as Spring Security's own @PreAuthorize
        // pointcut. Without it, an interface-annotated method runs with NO enforcement and no error.
        // The annotation is METHOD-only by design (each method names its action), so there is no
        // class-level pointcut half.
        Pointcut pointcut = new AnnotationMatchingPointcut(null, OpaPreAuthorize.class, true);

        AuthorizationManagerBeforeMethodInterceptor interceptor =
                new AuthorizationManagerBeforeMethodInterceptor(
                        pointcut, new DeferredAuthorizationManager(opaPreAuthorizeAuthorizationManager));
        interceptor.setOrder(INTERCEPTOR_ORDER);
        return interceptor;
    }

    /**
     * Resolves the real manager on the first decision, cached thereafter. Fail-closed posture is
     * untouched: a resolution failure (a misconfigured context) errors the request — it never allows.
     */
    private static final class DeferredAuthorizationManager
            implements AuthorizationManager<MethodInvocation> {

        private final Supplier<OpaPreAuthorizeAuthorizationManager> delegate;

        private DeferredAuthorizationManager(ObjectProvider<OpaPreAuthorizeAuthorizationManager> provider) {
            this.delegate = SingletonSupplier.of(provider::getObject);
        }

        @Override
        public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication, MethodInvocation invocation) {
            return delegate.get().authorize(authentication, invocation);
        }
    }
}
