package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Enforces {@link OpaPreAuthorize} as an {@link AuthorizationManager} over a {@link MethodInvocation}.
 *
 * <p>For an annotated method it: reads the {@link AbacContext.Subject} from the current
 * {@link AbacAuthentication}; resolves the resource (an {@link AbacDataObject} via {@code resource()},
 * else type/id via SpEL); looks up the caller's {@link RoleDefinition}; builds the single
 * {@link AbacContext}; and asks the {@link OpaClient}.
 *
 * <h2>Fail-closed</h2>
 * Unauthenticated, an unresolvable resource, a declared {@code resourceId} expression that resolves to
 * null/blank, a pointcut match without a resolvable annotation, or <em>any</em> exception while building
 * the context or calling OPA results in a denied decision — never an allow, never a silently-widened
 * (id-less) check. The second fail-closed layer (the first is inside {@link OpaClient}).
 */
public final class OpaPreAuthorizeAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    private static final Logger log = LoggerFactory.getLogger(OpaPreAuthorizeAuthorizationManager.class);
    private static final AuthorizationDecision DENY = new AuthorizationDecision(false);

    private final OpaClient opaClient;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public OpaPreAuthorizeAuthorizationManager(
            OpaClient opaClient, RoleDefinitionSupplier roleDefinitionSupplier) {
        this.opaClient = Objects.requireNonNull(opaClient, "opaClient");
        this.roleDefinitionSupplier =
                Objects.requireNonNull(roleDefinitionSupplier, "roleDefinitionSupplier");
    }

    /**
     * Spring Security 6.4 entry point. (Spring Security 7.0 renames this to {@code authorize(...)}; the
     * body is unchanged — keep this method until the baseline moves to 7.0.)
     */
    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, MethodInvocation invocation) {
        try {
            OpaPreAuthorize annotation = findAnnotation(invocation);
            if (annotation == null) {
                // This manager is bound to an @OpaPreAuthorize-matching pointcut, so "matched but no
                // annotation found" is a wiring inconsistency, not a legitimate state. Deny rather than
                // abstain — an abstain (null) would let the interceptor proceed unenforced.
                log.warn("OPA pre-authorize denied (fail-closed): pointcut matched '{}' but no "
                        + "@OpaPreAuthorize annotation was resolved", invocation.getMethod());
                return DENY;
            }

            AbacContext.Subject subject = currentSubject();
            if (subject == null) {
                log.debug("OPA pre-authorize denied: no authenticated AbacAuthentication");
                return DENY;
            }

            AbacContext.Resource resource = resolveResource(annotation, invocation);
            if (resource == null) {
                log.debug("OPA pre-authorize denied: resource could not be resolved for action '{}'",
                        annotation.action());
                return DENY;
            }

            RoleDefinition roleDefinition = roleDefinitionSupplier
                    .lookup(subject.id(), resource.type(), resource.id())
                    .orElse(null);

            AbacContext context =
                    new AbacContext(subject, annotation.action(), resource, roleDefinition, Map.of());

            return new AuthorizationDecision(opaClient.allow(context));
        } catch (Exception e) {
            // Fail-closed: any failure building the context or calling OPA denies.
            log.warn("OPA pre-authorize denied (fail-closed): {}", e.getClass().getSimpleName());
            return DENY;
        }
    }

    private static OpaPreAuthorize findAnnotation(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Class<?> targetClass = target != null ? target.getClass() : method.getDeclaringClass();
        // Resolve the most-specific (implementation) method behind any proxy/interface, so an
        // annotation on the concrete method is seen even when the invocation method is the interface one.
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        // findAnnotation searches the method, then the same method on superclasses and interfaces — so
        // an annotation declared on the interface method is honored. The annotation is METHOD-only, so
        // there is no class-level fallback.
        return AnnotationUtils.findAnnotation(specificMethod, OpaPreAuthorize.class);
    }

    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null;
    }

    private AbacContext.Resource resolveResource(OpaPreAuthorize annotation, MethodInvocation invocation) {
        // Bind the invocation's arguments once; reuse for every SpEL expression on this annotation.
        StandardEvaluationContext spelContext = new StandardEvaluationContext();
        bindArguments(spelContext, invocation);

        // 1) An AbacDataObject named by resource() wins (the caller holds the instance).
        if (!annotation.resource().isBlank()) {
            Object value = evaluate(annotation.resource(), spelContext);
            if (value instanceof AbacDataObject dataObject) {
                return new AbacContext.Resource(
                        dataObject.abacResourceType(), dataObject.abacResourceId(), dataObject.abacAttributes());
            }
            return null; // declared but unresolvable → deny
        }
        // 2) Otherwise resolve type (+ optional id) by SpEL.
        String type = asText(evaluate(annotation.resourceType(), spelContext));
        if (type == null || type.isBlank()) {
            return null; // no resource type → cannot decide
        }
        if (annotation.resourceId().isBlank()) {
            return new AbacContext.Resource(type, null, Map.of()); // type-level check, by declaration
        }
        String id = asText(evaluate(annotation.resourceId(), spelContext));
        if (id == null || id.isBlank()) {
            // A DECLARED resourceId that resolves to null/blank (a typo'd #param, or parameter names
            // unavailable at runtime) must deny — silently degrading to a type-level check would skip
            // per-id deny rules and per-resource role scoping, i.e. WIDEN access. Same posture as the
            // unresolvable resource() branch above.
            return null;
        }
        return new AbacContext.Resource(type, id, Map.of());
    }

    /** Evaluate a SpEL expression against a pre-bound context; blank expression → null. */
    private Object evaluate(String expression, StandardEvaluationContext spelContext) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        Expression parsed = expressionParser.parseExpression(expression);
        return parsed.getValue(spelContext);
    }

    /** Bind method args as #argN and (when names are available) #paramName. */
    private void bindArguments(StandardEvaluationContext context, MethodInvocation invocation) {
        Object[] args = invocation.getArguments();
        for (int i = 0; i < args.length; i++) {
            context.setVariable("arg" + i, args[i]);
            context.setVariable("p" + i, args[i]);
        }
        Object target = invocation.getThis();
        Class<?> targetClass = target != null ? target.getClass() : invocation.getMethod().getDeclaringClass();
        Method specificMethod = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
        String[] names = parameterNameDiscoverer.getParameterNames(specificMethod);
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                context.setVariable(names[i], args[i]);
            }
        }
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }
}
