package dev.dmitriikonovalov.opaabac.security;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;

/**
 * Registers {@link OpaPreAuthorizeAuthorizationManager} as a before-method security interceptor bound
 * to {@link OpaPreAuthorize}.
 *
 * <p>Requires {@code @EnableMethodSecurity} on the application. The advisor is ordered just before
 * Spring Security's own {@code @PreAuthorize} interceptor so an OPA deny short-circuits early.
 */
@Configuration(proxyBeanMethods = false)
public class OpaMethodSecurityConfiguration {

    /** Run just ahead of {@code @PreAuthorize} (whose interceptor order is 200). */
    static final int INTERCEPTOR_ORDER = 190;

    @Bean
    @Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
    @Order(INTERCEPTOR_ORDER)
    AuthorizationManagerBeforeMethodInterceptor opaPreAuthorizeMethodInterceptor(
            AuthorizationManager<MethodInvocation> opaPreAuthorizeAuthorizationManager) {

        // checkInherited = true: the annotation is found on the *most specific* method AND up the
        // type hierarchy — so @OpaPreAuthorize declared on an interface method is matched under
        // class-based (CGLIB) proxies too, the same posture as Spring Security's own @PreAuthorize
        // pointcut. Without it, an interface-annotated method runs with NO enforcement and no error.
        // The annotation is METHOD-only by design (each method names its action), so there is no
        // class-level pointcut half.
        Pointcut pointcut = new AnnotationMatchingPointcut(null, OpaPreAuthorize.class, true);

        AuthorizationManagerBeforeMethodInterceptor interceptor =
                new AuthorizationManagerBeforeMethodInterceptor(pointcut, opaPreAuthorizeAuthorizationManager);
        interceptor.setOrder(INTERCEPTOR_ORDER);
        return interceptor;
    }
}
