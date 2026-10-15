package dev.dmitriikonovalov.opaabac.mcp.authz;

import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * A request-scoped memoizing decorator over a {@link PrincipalCeilingSupplier} (ADR 0023, applied to the
 * tool-gate's ceiling): within one web request the delegate is consulted <strong>once</strong> per
 * {@code (subject, resourceType)} and its first outcome is replayed for the rest of the request.
 *
 * <p>The single-lookup semantics of the role-definition memo, verbatim:
 * <ul>
 *   <li><strong>All three outcomes are memoized</strong> — a resolved ceiling, the authoritative
 *       no-role ({@link Optional#empty()}), and the <em>outage</em>: any
 *       {@link DecisionIndeterminateException} the delegate throws is stored and <strong>re-thrown</strong>
 *       on every repeat of that key in the same request, so a {@code tools/list} pre-flight and the call
 *       that follows it cannot see a mixed snapshot. Any other exception propagates
 *       <strong>un-memoized</strong> — the next lookup reaches the delegate again.</li>
 *   <li><strong>The key is {@code (subject, resourceType)}</strong> and the memo lives in the request
 *       attributes, which die with the request: one caller's answer can never be served to another, and
 *       a revocation lands on the next request. The attribute is per decorator instance, so two decorated
 *       suppliers never share answers.</li>
 *   <li><strong>No request context is a clean pass-through</strong> — every lookup reaches the delegate.
 *       The decorator never throws from its own bookkeeping: a memo failure degrades to an extra lookup,
 *       never to a wrong decision.</li>
 * </ul>
 */
public final class MemoizingPrincipalCeilingSupplier implements PrincipalCeilingSupplier {

    private static final String ATTRIBUTE_PREFIX = MemoizingPrincipalCeilingSupplier.class.getName() + ":memo:";

    private static final AtomicLong INSTANCES = new AtomicLong();

    private final PrincipalCeilingSupplier delegate;

    /**
     * Per-instance: two decorated suppliers in one application never read each other's answers within a
     * request, even for the same {@code (subject, resourceType)}.
     */
    private final String attribute = ATTRIBUTE_PREFIX + INSTANCES.incrementAndGet();

    public MemoizingPrincipalCeilingSupplier(PrincipalCeilingSupplier delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<RoleDefinition> lookup(String subject, String resourceType) {
        Map<MemoKey, Object> memo = requestMemo();
        if (memo == null) {
            return delegate.lookup(subject, resourceType);
        }
        MemoKey key = new MemoKey(subject, resourceType);
        Object outcome = memo.get(key);
        if (outcome == null) {
            outcome = resolve(key);
            memo.put(key, outcome);
        }
        return replay(outcome);
    }

    /** One real delegate call; the value or the contractual outage captured for replay. */
    private Object resolve(MemoKey key) {
        try {
            return delegate.lookup(key.subject(), key.resourceType());
        } catch (DecisionIndeterminateException outage) {
            return new Outage(outage);
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<RoleDefinition> replay(Object outcome) {
        if (outcome instanceof Outage(DecisionIndeterminateException cause)) {
            throw cause;
        }
        return (Optional<RoleDefinition>) outcome;
    }

    /**
     * The request's memo map, or {@code null} when there is no bound request (or the attributes refuse
     * access): the pass-through signal. Never throws.
     */
    private Map<MemoKey, Object> requestMemo() {
        try {
            RequestAttributes request = RequestContextHolder.getRequestAttributes();
            if (request == null) {
                return null;
            }
            Object existing = request.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST);
            if (existing instanceof Map<?, ?> map) {
                return uncheckedMap(map);
            }
            Map<MemoKey, Object> fresh = new ConcurrentHashMap<>();
            request.setAttribute(attribute, fresh, RequestAttributes.SCOPE_REQUEST);
            return fresh;
        } catch (RuntimeException _) {
            return null; // lose the reuse, never the decision
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<MemoKey, Object> uncheckedMap(Map<?, ?> map) {
        return (Map<MemoKey, Object>) map;
    }

    /** Value-semantics key: one principal, one type. */
    private record MemoKey(String subject, String resourceType) {}

    /** The memoized third state: the contractual outage, re-thrown verbatim on replay. */
    private record Outage(DecisionIndeterminateException cause) {}
}
