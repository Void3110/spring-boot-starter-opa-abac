package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * A request-scoped memoizing decorator over the application's {@link RoleDefinitionSupplier}
 * (ADR 0023): within one web request, the delegate is consulted <strong>once</strong> per
 * {@code (userId, resourceType, resourceId)} key and its first outcome is replayed for the rest of
 * the request — <em>"one request sees exactly one resolve answer per target."</em>
 *
 * <p><strong>All three tri-state outcomes are memoized</strong> ({@link RoleDefinitionSupplier}'s
 * contract, replayed — never reinterpreted):
 * <ul>
 *   <li>{@code Optional.of(role)} — resolved (the perf headline: the measured 2/22/102 identical
 *       per-request resolves collapse to one real call);</li>
 *   <li>{@code Optional.empty()} — authoritative no-role (without it, a no-role caller's enriched
 *       page keeps the full fan-out: a deny-path DoS shape);</li>
 *   <li>the {@link RoleResolutionException} <strong>outage</strong> — stored as a marker and
 *       <strong>re-thrown</strong> on every repeat lookup of that key in the same request. Each
 *       caller keeps its own fail-closed degrade for the replayed throw (gate → deny; enrichment →
 *       omit; query path → direct-grant-only); a fully-degraded page is preferable to a
 *       mixed-snapshot page. Only {@code RoleResolutionException} — the SPI's contractual outage
 *       signal — is memoized; any other exception propagates un-memoized.</li>
 * </ul>
 *
 * <p><strong>Staleness contract:</strong> a resolve answer is a per-request snapshot; a mid-request
 * role change (including a revocation) takes effect at the next request boundary. Entries live as
 * request attributes ({@link RequestContextHolder}), which die with the request — there is no TTL,
 * no cross-request store, no invalidation protocol.
 *
 * <p><strong>No request context → a clean pass-through</strong> (the
 * {@link RequestAttributesResourceCache} degrade, verbatim): async executors, schedulers, non-web
 * callers and plain unit tests reach the delegate on every call, memoizing nothing. The decorator
 * never throws from its own bookkeeping — any bookkeeping failure degrades to pass-through, so a
 * memo bug can never become a deny.
 *
 * <p>Wired bean-level by the starter's {@code OpaResolveMemoAutoConfiguration}
 * ({@code opa.abac.resolve-memo.enabled}, default on), so app-side consumers that inject the
 * supplier directly (list authorizers) share the same memo as the gate and the enrichment advice.
 * Decoration composes outside any resolve-edge {@code CallGuard} living inside the delegate: a memo
 * hit never touches the guard, so the breaker samples at most one real call per key per request.
 */
public final class MemoizingRoleDefinitionSupplier implements RoleDefinitionSupplier {

    private static final String ATTRIBUTE = MemoizingRoleDefinitionSupplier.class.getName() + ":memo";

    private final RoleDefinitionSupplier delegate;

    public MemoizingRoleDefinitionSupplier(RoleDefinitionSupplier delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
        Map<MemoKey, Object> memo = requestMemo();
        if (memo == null) {
            return delegate.lookup(userId, resourceType, resourceId);
        }
        MemoKey key = new MemoKey(userId, resourceType, resourceId);
        Object outcome = memo.get(key);
        if (outcome == null) {
            outcome = resolve(key);
            memo.put(key, outcome);
        }
        return replay(outcome);
    }

    /** One real delegate call; the outcome (value or the contractual outage) captured for replay. */
    private Object resolve(MemoKey key) {
        try {
            return delegate.lookup(key.userId(), key.resourceType(), key.resourceId());
        } catch (RoleResolutionException outage) {
            return new Outage(outage);
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<RoleDefinition> replay(Object outcome) {
        if (outcome instanceof Outage outage) {
            throw outage.cause();
        }
        return (Optional<RoleDefinition>) outcome;
    }

    /**
     * The request's memo map, or {@code null} when there is no bound request (or the request
     * attributes refuse access — e.g. a completed request): the pass-through signal. Never throws.
     */
    private static Map<MemoKey, Object> requestMemo() {
        try {
            RequestAttributes request = RequestContextHolder.getRequestAttributes();
            if (request == null) {
                return null;
            }
            Object existing = request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (existing instanceof Map<?, ?> map) {
                return (Map<MemoKey, Object>) uncheckedMap(map);
            }
            Map<MemoKey, Object> fresh = new ConcurrentHashMap<>();
            request.setAttribute(ATTRIBUTE, fresh, RequestAttributes.SCOPE_REQUEST);
            return fresh;
        } catch (RuntimeException bookkeepingFailure) {
            return null; // lose the reuse, never the decision
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<MemoKey, Object> uncheckedMap(Map<?, ?> map) {
        return (Map<MemoKey, Object>) map;
    }

    /** Value-semantics key; a {@code null} resourceId (type-level check) is its own distinct key. */
    record MemoKey(String userId, String resourceType, String resourceId) {}

    /** The memoized third state: the contractual outage, re-thrown verbatim on replay. */
    private record Outage(RoleResolutionException cause) {}
}
