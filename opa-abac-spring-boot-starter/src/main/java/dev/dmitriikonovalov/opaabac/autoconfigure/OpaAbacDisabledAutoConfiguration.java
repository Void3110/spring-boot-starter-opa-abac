package dev.dmitriikonovalov.opaabac.autoconfigure;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The fail-closed side of the master switch (ADR 0038). {@code opa.abac.enabled=false} removes every
 * starter bean, the {@code @OpaPreAuthorize} advisor included, so a method that declares a gate would run
 * with no decision at all — in code that still reads as secured. This configuration is active exactly when
 * the switch is off, and registers an {@link UngatedMethodsGuard} that refuses that state at startup unless
 * the application acknowledges it with {@code opa.abac.allow-ungated-methods=true}.
 *
 * <p>Gated on the same classes as the starter's security beans: without Spring Security + web on the
 * classpath the advisor is never registered, enabled or not, so turning the starter off changes nothing
 * there and there is nothing to guard.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "opa.abac", name = "enabled", havingValue = "false")
@ConditionalOnClass(name = {
    "org.springframework.security.web.SecurityFilterChain",
    "org.springframework.web.filter.OncePerRequestFilter"
})
public class OpaAbacDisabledAutoConfiguration {

    /**
     * Reads the one acknowledgment property directly rather than binding {@link OpaAbacProperties}: with the
     * starter off, a malformed value anywhere else under {@code opa.abac} must not start failing the boot.
     */
    @Bean
    UngatedMethodsGuard opaAbacUngatedMethodsGuard(
            ConfigurableListableBeanFactory beanFactory, Environment environment) {
        BindResult<Boolean> acknowledged =
                Binder.get(environment).bind(UngatedMethodsGuard.ACKNOWLEDGEMENT, Boolean.class);
        return new UngatedMethodsGuard(beanFactory, acknowledged.isBound() && acknowledged.get());
    }
}
