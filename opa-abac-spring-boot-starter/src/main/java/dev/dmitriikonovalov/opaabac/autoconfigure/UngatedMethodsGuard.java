package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.security.OpaMethodSecurityConfiguration;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * With the starter disabled, finds every bean method that declares {@code @OpaPreAuthorize} and refuses to
 * start while there are any — or, when {@value #ACKNOWLEDGEMENT} is {@code true}, starts and names them in
 * a WARN (ADR 0038). A gate the application declared never silently stops deciding.
 *
 * <p>The match is the advisor's own pointcut ({@link OpaMethodSecurityConfiguration#opaPreAuthorizePointcut()}),
 * applied the way the auto-proxy creator applies it ({@link AopUtils#canApply(Pointcut, Class)}), so the guard
 * and the advisor cannot disagree about which methods are gated: an annotation declared on an interface method
 * counts. It does not depend on {@code @EnableMethodSecurity} — the annotation declares the gate, and turning
 * the starter off is an explicit act, which is what the acknowledgment is for.
 *
 * <p>Runs once every eager singleton exists, before the web server starts. An instantiated bean is judged by
 * its target class behind any proxy; a lazy, prototype or scoped bean by its predicted type.
 */
final class UngatedMethodsGuard implements SmartInitializingSingleton {

    /** The acknowledgment that running declared gates without a decision is intended. */
    static final String ACKNOWLEDGEMENT = "opa.abac.allow-ungated-methods";

    private static final Logger log = LoggerFactory.getLogger(UngatedMethodsGuard.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final boolean allowUngated;

    UngatedMethodsGuard(ConfigurableListableBeanFactory beanFactory, boolean allowUngated) {
        this.beanFactory = beanFactory;
        this.allowUngated = allowUngated;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> gated = gatedMethods();
        if (gated.isEmpty()) {
            return;
        }
        String names = String.join(", ", gated);
        if (!allowUngated) {
            throw new IllegalStateException("opa.abac.enabled=false removes the @OpaPreAuthorize advisor, so "
                    + gated.size() + " method(s) that declare an authorization gate would run with NO decision: "
                    + names + ". Remove opa.abac.enabled=false to enforce them, or set " + ACKNOWLEDGEMENT
                    + "=true to run them ungated on purpose (ADR 0038).");
        }
        log.warn("opa.abac.enabled=false and {}=true: {} @OpaPreAuthorize method(s) run with NO authorization "
                + "decision: {}", ACKNOWLEDGEMENT, gated.size(), names);
    }

    /** {@code Type#method} for every gated method — sorted, overloads and scoped-proxy pairs collapsed. */
    Set<String> gatedMethods() {
        Pointcut pointcut = OpaMethodSecurityConfiguration.opaPreAuthorizePointcut();
        Set<String> gated = new TreeSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = targetType(beanName);
            if (type != null && AopUtils.canApply(pointcut, type)) {
                Set<String> methods = methodNames(pointcut.getMethodMatcher(), type);
                // canApply is the authority on WHETHER a bean is gated; the names are only for the message,
                // so a bean it matched is reported even if naming its methods came up empty.
                gated.addAll(methods.isEmpty() ? Set.of(displayName(type)) : methods);
            }
        }
        return gated;
    }

    private Class<?> targetType(String beanName) {
        if (beanFactory.getBeanDefinition(beanName).isAbstract()) {
            return null;
        }
        Object singleton = beanFactory.getSingleton(beanName);
        if (singleton != null && !(singleton instanceof FactoryBean<?>)) {
            return AopProxyUtils.ultimateTargetClass(singleton);
        }
        return beanFactory.getType(beanName, false);
    }

    /** The methods {@code canApply} inspects — the user class unless a JDK proxy, plus every interface. */
    private static Set<String> methodNames(MethodMatcher matcher, Class<?> type) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        if (!Proxy.isProxyClass(type)) {
            classes.add(ClassUtils.getUserClass(type));
        }
        classes.addAll(ClassUtils.getAllInterfacesForClassAsSet(type));
        Set<String> names = new TreeSet<>();
        for (Class<?> candidate : classes) {
            for (Method method : ReflectionUtils.getAllDeclaredMethods(candidate)) {
                if (matcher.matches(method, type)) {
                    names.add(displayName(type) + "#" + method.getName());
                }
            }
        }
        return names;
    }

    private static String displayName(Class<?> type) {
        return ClassUtils.getShortName(ClassUtils.getUserClass(type));
    }
}
