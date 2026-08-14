package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.AncestorChainSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaDecision;
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
 * {@link AbacAuthentication}; resolves the resource (an {@link AbacResource} via {@code resource()},
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
 * {@link AbacResourceCache} for handler reuse. Without support (or with the kill-switch off), the
 * built context is byte-identical to the pre-resolution manager's; type-level checks (no
 * {@code resourceId}) never engage the resolver.
 *
 * <h2>Root-attribute enrichment (ADR 0032)</h2>
 * When the governing target is <em>distinct</em> from the decided leaf, the manager also resolves that
 * target and threads its attributes into the context as {@code resource.root_attributes} — the input
 * a policy needs to gate a child decision on ancestor state. Any failure leaves the field
 * <b>absent</b> (never an exception, never a deny by itself); the three wire states are absent =
 * unproven, <code>&#123;&#125;</code> = fetched-and-untagged, populated = as tagged.
 *
 * <p><b>This amends the cache contract, deliberately.</b> The root resolve is read-through-memoized in
 * the same {@link AbacResourceCache}, and its {@code put} is <em>decision-independent</em> — after a
 * successful resolve, before the OPA call. So an entry is a <b>resolved</b> snapshot, no longer
 * necessarily an <b>authorized</b> one, and a cache hit must never be read as proof that anything was
 * allowed. The older "the gate never reads the cache" note still holds where it matters — for the
 * <em>decided leaf</em>, which is always resolved fresh; the memo covers only the governing root.
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

    /**
     * Which allowed reads are privileged enough to audit, in the ADOPTER's vocabulary — {@code null}
     * (the default) means the privileged-read event is never emitted. See
     * {@link PrivilegedReadAuditPolicy}.
     */
    private final PrivilegedReadAuditPolicy privilegedReadAuditPolicy;
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
        this(opaClient, roleDefinitionSupplier, resolutionSupport, null);
    }

    /**
     * @param privilegedReadAuditPolicy which allowed reads are privileged enough to audit, in this
     *                                  adopter's own vocabulary, or {@code null} to emit no
     *                                  privileged-read event (the default — see
     *                                  {@link PrivilegedReadAuditPolicy})
     */
    public OpaPreAuthorizeAuthorizationManager(
            OpaClient opaClient,
            RoleDefinitionSupplier roleDefinitionSupplier,
            ResourceResolutionSupport resolutionSupport,
            PrivilegedReadAuditPolicy privilegedReadAuditPolicy) {
        this.opaClient = Objects.requireNonNull(opaClient, "opaClient");
        this.roleDefinitionSupplier =
                Objects.requireNonNull(roleDefinitionSupplier, "roleDefinitionSupplier");
        this.resolutionSupport = resolutionSupport;
        this.privilegedReadAuditPolicy = privilegedReadAuditPolicy;
    }

    /**
     * The decision entry point — {@code authorize(...)} since Spring Security 6.4 (the interceptor
     * dispatches here; Security 7 makes it the abstract method). Overridden with the covariant
     * {@link AuthorizationDecision} return so callers keep the narrower type.
     */
    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication, MethodInvocation invocation) {
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

            ResolvedCheck resolved = enrichWithRootAttributes(resolveCheck(annotation, invocation));
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

            OpaDecision decision = opaClient.decide(context);
            if (decision == null) {
                // A client breaking the never-null contract. Deny explicitly rather than let an NPE fall
                // into the catch below — same outcome, but legible in the log and pinned by a test.
                log.warn("OPA pre-authorize denied (fail-closed): OpaClient.decide returned null");
                return DENY;
            }
            boolean allowed = decision.allow();
            if (allowed && resolutionSupport != null && resolved.instance() != null) {
                // Write-through on allow only: the handler may reuse the authorized snapshot. The DECIDED
                // LEAF is never read back by the gate — it is always resolved fresh, so this entry can
                // never become an input to its own decision. (The governing ROOT is a separate, and
                // deliberately decision-independent, memo — see the class javadoc.)
                resolutionSupport.cache().put(resolved.resource().type(), resolved.resource().id(),
                        resolved.instance());
            }
            if (allowed) {
                auditPrivilegedRead(subject, roleDefinition, resolved);
                return new AuthorizationDecision(true);
            }
            if (decision.denyReason() != null) {
                // A structured deny: the policy says a fresh second factor is the SOLE blocker. Carry the
                // reason — plus the log-only coordinates the enforcement point cannot re-derive — so the
                // advice can mint the challenge. Still a denied decision to everything that only asks
                // isGranted().
                return new StepUpRequiredDecision(
                        decision.denyReason(),
                        resolved.resource().type(),
                        resolved.resource().id(),
                        resolved.roleId());
            }
            return DENY;
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

    /**
     * Emit {@code PRIVILEGED_READ} (ADR 0030 §8 + Amendment 7) when — and only when — an <b>allowed</b>
     * decision matched the adopter's {@link PrivilegedReadAuditPolicy}: the resolved role carries the
     * configured <b>provenance</b>, and the governing root's configured <b>tier attribute</b> holds one of
     * the configured <b>tier values</b>. With no policy configured, nothing is emitted — the words
     * {@code supervised} and {@code production} are this repo's EXAMPLE vocabulary, not the library's.
     *
     * <p><b>Elevation is implied by the allow and never re-derived here.</b> The policy already required
     * it; re-checking `acr`/`auth_time` app-side would mean a second copy of the LoA map and the freshness
     * window, and the whole point of ADR 0030 Amendment 3 is that exactly one window exists. The claims
     * are logged verbatim, not interpreted.
     *
     * <p>The tier test mirrors the policy's {@code root_env_values} — the cardinality twin: a tag value in
     * this model is a scalar string <em>or</em> a string array, and a bare {@code equals} would miss
     * {@code ["production", "staging"]}. Absent root attributes mean no event: nothing proved this read
     * was privileged, which is also the state the policy treats as an unproven (closed) tier.
     */
    private void auditPrivilegedRead(
            AbacContext.Subject subject, RoleDefinition roleDefinition, ResolvedCheck resolved) {
        if (privilegedReadAuditPolicy == null
                || !privilegedReadAuditPolicy.matches(roleDefinition, resolved.resource().rootAttributes())) {
            return;
        }
        AbacAuditLogger.privilegedRead(
                subject.id(),
                subject.attributes(),
                resolved.resource().type(),
                resolved.resource().id(),
                resolved.roleId(),
                privilegedReadAuditPolicy.provenance());
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

        // 1) An AbacResource named by resource() wins (the caller holds the instance). Decision
        //    inputs are unchanged by resolution support; the instance is cached on allow.
        if (!annotation.resource().isBlank()) {
            Object value = evaluate(annotation.resource(), spelContext);
            if (value instanceof AbacResource dataObject) {
                AbacContext.Resource resource = new AbacContext.Resource(
                        dataObject.abacResourceType(), dataObject.abacResourceId(), dataObject.abacAttributes());
                return withRoleResourceOverride(
                        annotation, spelContext,
                        new ResolvedCheck(resource, resource.type(), resource.id(), dataObject));
            }
            return null; // declared but unresolvable → deny
        }
        // 2) Otherwise resolve type (+ optional id) by SpEL.
        String type = asText(evaluate(annotation.resourceType(), spelContext));
        if (type == null || type.isBlank()) {
            return null; // no resource type → cannot decide
        }
        if (annotation.resourceId().isBlank()) {
            // Type-level check, by declaration — never engages the resolver, caches nothing. The role is
            // looked up on (type, null) UNLESS a roleResource override moves it to a governing parent
            // (the child create/list case: no leaf instance to walk up from, so name the parent explicitly).
            return withRoleResourceOverride(annotation, spelContext,
                    new ResolvedCheck(new AbacContext.Resource(type, null, Map.of()), type, null, null));
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
            return withRoleResourceOverride(annotation, spelContext,
                    new ResolvedCheck(new AbacContext.Resource(type, id, Map.of()), type, id, null));
        }
        return withRoleResourceOverride(annotation, spelContext, resolveInstance(type, id));
    }

    /**
     * Apply the {@link OpaPreAuthorize#roleResourceType()}/{@link OpaPreAuthorize#roleResourceId()}
     * override: when both are declared and resolve to non-blank, the role is looked up on
     * {@code (roleResourceType, roleResourceId)} (a governing parent) instead of the decided resource's
     * own coordinates — the decided resource (its type, the queried policy, its attributes) is unchanged.
     * Used for type-level child create/list gates that must resolve the role on the parent catalog so the
     * policy's inheritable-ancestor grant can fire. A declared-but-unresolvable override denies
     * (fail-closed), never silently falling back to the original coordinates.
     *
     * @return the check with overridden role coordinates, the original check when no override is declared,
     *     or {@code null} (deny) when the override is declared but unresolvable / the base check is null
     */
    private ResolvedCheck withRoleResourceOverride(
            OpaPreAuthorize annotation, StandardEvaluationContext spelContext, ResolvedCheck base) {
        if (base == null) {
            return null;
        }
        boolean typeDeclared = !annotation.roleResourceType().isBlank();
        boolean idDeclared = !annotation.roleResourceId().isBlank();
        if (!typeDeclared && !idDeclared) {
            return base; // no override → today's behavior
        }
        String roleType = asText(evaluate(annotation.roleResourceType(), spelContext));
        String roleId = asText(evaluate(annotation.roleResourceId(), spelContext));
        if (roleType == null || roleType.isBlank() || roleId == null || roleId.isBlank()) {
            // Declared but unresolvable → deny (never widen by falling back to the decided resource).
            log.debug("OPA pre-authorize denied: role-resource override declared but unresolvable");
            return null;
        }
        return new ResolvedCheck(base.resource(), roleType, roleId, base.instance());
    }

    /**
     * Root-attribute enrichment (ADR 0032): thread the <b>governing target's</b> tag map into the decided
     * resource as {@code root_attributes}, so a policy can gate a child decision on ancestor state.
     *
     * <p><b>One rule for both paths</b>, because both already computed the same thing: the governing
     * target is exactly the {@code (type, id)} the role is looked up on — the ancestor chain's root on
     * the instance path, the {@code roleResource} override target on the type-level child gates. Doing it
     * here, after the override has been applied, is what keeps the two consistent: the enriched
     * attributes always describe the same resource the role was resolved on, so a policy can never read
     * {@code root_attributes} as belonging to some other ancestor.
     *
     * <p>The field stays <b>absent</b> when there is nothing to prove or nothing proved it: no resolution
     * support, a type-level check with no override, a leaf that <em>is</em> its own governing root (its
     * own attributes already carry its tags, and ADR 0030 §1 keeps a root's own read ungated), or
     * <b>any</b> failure resolving the target. Absence is never a deny by itself and never an exception
     * out of the manager — the policy decides what absence means: a membership decision is indifferent to
     * it, while a supervised decision treats an unproven tier as closed.
     */
    private ResolvedCheck enrichWithRootAttributes(ResolvedCheck check) {
        if (check == null || resolutionSupport == null) {
            return check;
        }
        String rootType = check.roleType();
        String rootId = check.roleId();
        if (rootType == null || rootId == null) {
            return check; // a type-level check with no governing target
        }
        AbacContext.Resource leaf = check.resource();
        if (rootType.equals(leaf.type()) && rootId.equals(leaf.id())) {
            return check; // the leaf IS the root — its own attributes already carry its tags
        }
        Map<String, Object> rootAttributes = resolveRootAttributes(rootType, rootId);
        if (rootAttributes == null) {
            return check; // unproven — the absent state, state one of ADR 0032's three
        }
        return new ResolvedCheck(
                new AbacContext.Resource(
                        leaf.type(), leaf.id(), leaf.attributes(), leaf.ancestors(), rootAttributes),
                rootType,
                rootId,
                check.instance());
    }

    /**
     * Resolve the governing target's attributes, <b>read-through-memoized</b> in the request cache so a
     * request pays at most one extra resolver call across its gate and instance checks.
     *
     * <p><b>Adopter caveat about that memo.</b> The read-through takes whatever the request cache holds
     * for {@code (rootType, rootId)}, and the allow-write-through above stores the resolved instance —
     * which, for the {@link OpaPreAuthorize#resource()} form, is an object the <em>caller</em> supplied
     * rather than one the resolver loaded. So in an application that (a) uses that annotation form for a
     * type which is also a governing root, and (b) makes a child check on the same root later in the
     * same request, the child's {@code root_attributes} would come from the caller's object. The scope
     * is one request and one subject — nothing crosses either — and no such call site exists in this
     * repository, but an adopter relying on root attributes for a security decision should resolve
     * governing roots through the resolver rather than through the {@code resource()} form.
     *
     * @return the target's attributes, or {@code null} on <em>any</em> failure — resolver empty, resolver
     *     throw, or a target that reports null attributes. A tag lookup must never become a member-facing
     *     outage, so nothing here propagates. Note the direction of the null-attributes case: it lands on
     *     <b>absent</b> (unproven, closed), never on an empty map (untagged, open) — when in doubt about
     *     what the root says, the honest answer is that we do not know.
     */
    private Map<String, Object> resolveRootAttributes(String rootType, String rootId) {
        try {
            AbacResource cached =
                    resolutionSupport.cache().get(rootType, rootId, AbacResource.class).orElse(null);
            if (cached != null) {
                return cached.abacAttributes();
            }

            AbacResource root = resolutionSupport.resolver().resolve(rootType, rootId).orElse(null);
            if (root == null) {
                log.debug("root-attribute enrichment: '{}/{}' did not resolve — tier left unproven",
                        rootType, rootId);
                return null;
            }
            // Decision-INDEPENDENT put (see the cache note in this class's javadoc): the root is memoized
            // as resolved, not as authorized.
            resolutionSupport.cache().put(rootType, rootId, root);
            return root.abacAttributes();
        } catch (RuntimeException e) {
            log.debug("root-attribute enrichment for '{}/{}' failed ({}) — tier left unproven",
                    rootType, rootId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The full per-instance resolution for a declared {@code resourceId}: instance → ancestors →
     * governing root. The two failure semantics are split — instance empty returns {@code null} (deny;
     * a resolver throw propagates to the fail-closed catch, also deny), an ancestor failure only
     * collapses the chain — and must never be confused in either direction.
     */
    private ResolvedCheck resolveInstance(String type, String id) {
        AbacResource instance = resolutionSupport.resolver().resolve(type, id).orElse(null);
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
