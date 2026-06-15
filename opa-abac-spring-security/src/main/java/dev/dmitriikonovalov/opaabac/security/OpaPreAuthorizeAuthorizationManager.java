package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import dev.dmitriikonovalov.opaabac.core.AncestorChainSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.lang.reflect.Method;
import java.util.List;
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
 * <h2>Resource resolution (opt-in)</h2>
 * With a {@link ResourceResolutionSupport} present, a check that declares a {@code resourceId} resolves
 * the <em>instance</em> behind it and decides on its real attributes and ancestor chain, the role
 * looked up <strong>once on the governing root</strong> — {@code ancestors.isEmpty() ? leaf :
 * ancestors.get(0)}, exactly {@code HierarchicalAuthorizer}'s rule. The two failure semantics are
 * split and must never be confused: instance resolution empty/throws → <strong>deny</strong> (never an
 * attribute-less context, which could skip attribute-keyed deny rules); ancestor resolution throws →
 * the chain <strong>collapses to empty</strong> and the decision proceeds direct-grant-only (never a
 * partial chain, never a stripped direct grant). On allow the instance is written through to the
 * {@link AbacResourceCache} for handler reuse — the gate itself never reads the cache. Without
 * support (or with the kill-switch off), the built context is byte-identical to the pre-resolution
 * manager's; type-level checks (no {@code resourceId}) never engage the resolver.
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
    private final ResourceResolutionSupport resolutionSupport;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public OpaPreAuthorizeAuthorizationManager(
            OpaClient opaClient, RoleDefinitionSupplier roleDefinitionSupplier) {
        this(opaClient, roleDefinitionSupplier, null);
    }

    /**
     * @param resolutionSupport the resolution collaborators, or {@code null} for the pre-resolution,
     *                          reference-based behavior (byte-identical contexts)
     */
    public OpaPreAuthorizeAuthorizationManager(
            OpaClient opaClient,
            RoleDefinitionSupplier roleDefinitionSupplier,
            ResourceResolutionSupport resolutionSupport) {
        this.opaClient = Objects.requireNonNull(opaClient, "opaClient");
        this.roleDefinitionSupplier =
                Objects.requireNonNull(roleDefinitionSupplier, "roleDefinitionSupplier");
        this.resolutionSupport = resolutionSupport;
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

            ResolvedCheck resolved = resolveCheck(annotation, invocation);
            if (resolved == null) {
                log.debug("OPA pre-authorize denied: resource could not be resolved for action '{}'",
                        annotation.action());
                return DENY;
            }

            RoleDefinition roleDefinition = roleDefinitionSupplier
                    .lookup(subject.id(), resolved.roleType(), resolved.roleId())
                    .orElse(null);

            AbacContext context =
                    new AbacContext(subject, annotation.action(), resolved.resource(), roleDefinition, Map.of());

            boolean allowed = opaClient.allow(context);
            if (allowed && resolutionSupport != null && resolved.instance() != null) {
                // Write-through on allow only: the handler may reuse the authorized snapshot. The gate
                // itself never reads this cache, so it can never become an input to a decision.
                resolutionSupport.cache().put(resolved.resource().type(), resolved.resource().id(),
                        resolved.instance());
            }
            return new AuthorizationDecision(allowed);
        } catch (RoleResolutionException e) {
            // B2: role-source outage → deny, never the realm fallback (ADR 0014). An outage makes the
            // role UNKNOWN; building an empty-role context would let the policy's realm fallback decide,
            // widening access. Deny here so OPA is never asked. (The broad catch below would also catch
            // this, but the explicit catch makes the fail-closed decision legible and tested.)
            log.debug("OPA pre-authorize denied: role-source outage ({})", e.getClass().getSimpleName());
            return DENY;
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

    /**
     * Everything one check resolved to: the context resource, the {@code (type, id)} the role is looked
     * up on (the governing root under resolution; the resource's own coordinates otherwise), and the
     * loaded instance to cache on allow ({@code null} when there is none).
     */
    private record ResolvedCheck(AbacContext.Resource resource, String roleType, String roleId, Object instance) {}

    private ResolvedCheck resolveCheck(OpaPreAuthorize annotation, MethodInvocation invocation) {
        // Bind the invocation's arguments once; reuse for every SpEL expression on this annotation.
        StandardEvaluationContext spelContext = new StandardEvaluationContext();
        bindArguments(spelContext, invocation);

        // 1) An AbacDataObject named by resource() wins (the caller holds the instance). Decision
        //    inputs are unchanged by resolution support; the instance is cached on allow.
        if (!annotation.resource().isBlank()) {
            Object value = evaluate(annotation.resource(), spelContext);
            if (value instanceof AbacDataObject dataObject) {
                AbacContext.Resource resource = new AbacContext.Resource(
                        dataObject.abacResourceType(), dataObject.abacResourceId(), dataObject.abacAttributes());
                return new ResolvedCheck(resource, resource.type(), resource.id(), dataObject);
            }
            return null; // declared but unresolvable → deny
        }
        // 2) Otherwise resolve type (+ optional id) by SpEL.
        String type = asText(evaluate(annotation.resourceType(), spelContext));
        if (type == null || type.isBlank()) {
            return null; // no resource type → cannot decide
        }
        if (annotation.resourceId().isBlank()) {
            // Type-level check, by declaration — never engages the resolver, caches nothing.
            return new ResolvedCheck(new AbacContext.Resource(type, null, Map.of()), type, null, null);
        }
        String id = asText(evaluate(annotation.resourceId(), spelContext));
        if (id == null || id.isBlank()) {
            // A DECLARED resourceId that resolves to null/blank (a typo'd #param, or parameter names
            // unavailable at runtime) must deny — silently degrading to a type-level check would skip
            // per-id deny rules and per-resource role scoping, i.e. WIDEN access. Same posture as the
            // unresolvable resource() branch above.
            return null;
        }
        if (resolutionSupport == null) {
            // Pre-resolution, reference-based behavior — byte-identical context, leaf role lookup.
            return new ResolvedCheck(new AbacContext.Resource(type, id, Map.of()), type, id, null);
        }
        return resolveInstance(type, id);
    }

    /**
     * The full per-instance resolution for a declared {@code resourceId}: instance → ancestors →
     * governing root. The two failure semantics are split — instance empty returns {@code null} (deny;
     * a resolver throw propagates to the fail-closed catch, also deny), an ancestor failure only
     * collapses the chain — and must never be confused in either direction.
     */
    private ResolvedCheck resolveInstance(String type, String id) {
        AbacDataObject instance = resolutionSupport.resolver().resolve(type, id).orElse(null);
        if (instance == null) {
            log.debug("OPA pre-authorize denied (fail-closed): resource '{}/{}' did not resolve", type, id);
            return null;
        }
        List<ParentRef> ancestors = List.of();
        AncestorChainSupplier chainSupplier = resolutionSupport.ancestorChainSupplier();
        if (chainSupplier != null) {
            try {
                List<ParentRef> chain = chainSupplier.ancestorsOf(type, id);
                ancestors = chain == null ? List.<ParentRef>of() : chain;
            } catch (RuntimeException e) {
                // Ancestor failure collapses to the empty chain — direct-grant-only, never a partial
                // chain, never a deny by itself (that would strip direct grants).
                log.debug("OPA pre-authorize: ancestor chain for '{}/{}' collapsed to empty ({})",
                        type, id, e.getClass().getSimpleName());
            }
        }
        // The role is resolved ONCE, on the governing root — HierarchicalAuthorizer's rule verbatim:
        // "the chain's first element, or the leaf itself when there is no inheritable lineage."
        ParentRef governingRoot = ancestors.isEmpty() ? new ParentRef(type, id) : ancestors.get(0);
        AbacContext.Resource resource =
                new AbacContext.Resource(type, id, instance.abacAttributes(), ancestors);
        return new ResolvedCheck(resource, governingRoot.type(), governingRoot.id(), instance);
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
