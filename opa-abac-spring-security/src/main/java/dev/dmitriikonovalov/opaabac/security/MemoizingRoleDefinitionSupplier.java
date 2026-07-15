package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.ResolveTarget;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * The batch form (ADR 0024), memo-integrated: memoized keys are served from the request memo and
     * <strong>excluded</strong> from the delegated set; the misses go down as <strong>one</strong>
     * {@code delegate.lookupAll} call; every returned entry is memoized; the merged (hits + fresh)
     * map is returned strictly complete. Fail-closed edges:
     * <ul>
     *   <li>a memoized <em>outage</em> hit re-throws — this request's answer for that target IS the
     *       outage, and a batch never yields partial roles (whole-batch semantics);</li>
     *   <li>a delegate whole-batch outage memoizes the <strong>outage marker for every missed
     *       target</strong> before re-throwing — a later single {@link #lookup} of any of them
     *       replays the throw without touching the delegate;</li>
     *   <li>a delegate return violating strict completeness (missing/extra entry) is treated as a
     *       malformed batch → whole-batch outage, memoized the same way — the memo never launders a
     *       partial map into a complete-looking one.</li>
     * </ul>
     * No request bound → pure pass-through, like {@link #lookup}.
     */
    @Override
    public Map<ResolveTarget, Optional<RoleDefinition>> lookupAll(String userId, Set<ResolveTarget> targets) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        Map<MemoKey, Object> memo = requestMemo();
        if (memo == null) {
            return delegate.lookupAll(userId, targets);
        }
        Map<ResolveTarget, Optional<RoleDefinition>> merged = new HashMap<>();
        Set<ResolveTarget> misses = new LinkedHashSet<>();
        for (ResolveTarget target : targets) {
            Object outcome = memo.get(new MemoKey(userId, target.resourceType(), target.resourceId()));
            if (outcome == null) {
                misses.add(target);
            } else {
                merged.put(target, replay(outcome)); // an outage hit re-throws here — whole batch
            }
        }
        if (!misses.isEmpty()) {
            Map<ResolveTarget, Optional<RoleDefinition>> fresh = resolveBatch(userId, misses, memo);
            for (ResolveTarget target : misses) {
                Optional<RoleDefinition> entry = fresh.get(target);
                memo.put(new MemoKey(userId, target.resourceType(), target.resourceId()), entry);
                merged.put(target, entry);
            }
        }
        return Map.copyOf(merged);
    }

    /**
     * One real batch call for the misses; a whole-batch outage — thrown by the delegate <em>or</em>
     * synthesized from a strict-completeness violation — is memoized for every missed target, then
     * re-thrown.
     */
    private Map<ResolveTarget, Optional<RoleDefinition>> resolveBatch(
            String userId, Set<ResolveTarget> misses, Map<MemoKey, Object> memo) {
        RoleResolutionException outage;
        try {
            Map<ResolveTarget, Optional<RoleDefinition>> fresh = delegate.lookupAll(userId, misses);
            if (fresh.size() == misses.size() && fresh.keySet().containsAll(misses)) {
                return fresh;
            }
            outage = new RoleResolutionException(
                    "lookupAll contract violation: expected exactly one entry per requested target ("
                            + misses.size() + " requested, " + fresh.size() + " returned)");
        } catch (RoleResolutionException e) {
            outage = e;
        }
        Outage marker = new Outage(outage);
        for (ResolveTarget target : misses) {
            memo.put(new MemoKey(userId, target.resourceType(), target.resourceId()), marker);
        }
        throw outage;
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
        if (outcome instanceof Outage(RoleResolutionException cause)) {
            throw cause;
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
                return uncheckedMap(map);
            }
            Map<MemoKey, Object> fresh = new ConcurrentHashMap<>();
            request.setAttribute(ATTRIBUTE, fresh, RequestAttributes.SCOPE_REQUEST);
            return fresh;
        } catch (RuntimeException _) {
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
