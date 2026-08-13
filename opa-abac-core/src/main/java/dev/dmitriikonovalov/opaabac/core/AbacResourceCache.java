package dev.dmitriikonovalov.opaabac.core;

import java.util.Optional;

/**
 * A request-scoped, write-through cache of resources the gate has <em>resolved</em>: the
 * authorization manager {@code put}s the resolved instance, and the handler (or a later read-side
 * consumer, e.g. action enrichment) {@code get}s it back instead of issuing a second load — the
 * response is then exactly the state the decision saw.
 *
 * <p><strong>An attribute snapshot, never a verdict.</strong> A cached entry is the <em>resolved
 * resource</em> (its attributes — tags, hierarchy) as it was when written; <em>presence is not an
 * authorization</em>. Every per-action decision — gate or affordance enrichment — is computed fresh
 * against this snapshot; an entry never short-circuits a verdict to "allowed".
 *
 * <p><strong>The decided leaf is never read back; the governing root is a decision-read memo.</strong>
 * For the resource being decided, every evaluation resolves fresh — a cached instance can never
 * widen (or stale-out) that decision. For the decided leaf the manager {@code put}s only after an
 * allow. The <em>governing root</em> is different since the root-attribute enrichment (ADR 0032):
 * the manager read-through-memoizes the root here — a {@code get} feeds the root's attributes into
 * the decision input ({@code root_attributes}), and the {@code put} is decision-independent (the
 * root is memoized as <em>resolved</em>, not as <em>authorized</em>). Because entries can feed a
 * decision, <strong>an implementation MUST be strictly request-bound</strong>: the key carries no
 * subject, so anything wider (a session or shared cache) would serve one caller's resolved root to
 * another. Entries are request-bounded — nothing survives the request, and outside a web request the
 * default implementation degrades to a no-op (resolution still feeds the decision; only the reuse is
 * lost).
 *
 * <p>This interface is framework-agnostic (it lives in {@code opa-abac-core}); the default
 * request-attributes implementation lives in {@code opa-abac-spring-security}.
 */
public interface AbacResourceCache {

    /**
     * Look up the resolved instance cached for this request, if any.
     *
     * @param resourceType the resource type the gate resolved
     * @param resourceId   the resource id the gate resolved
     * @param as           the expected type; a cached instance of a different type yields empty
     * @return the instance the decision was made on, or empty (not cached / wrong type / no request)
     */
    <T> Optional<T> get(String resourceType, String resourceId, Class<T> as);

    /**
     * Cache a resolved instance for the remainder of this request. Called by the authorization
     * manager — on allow for the decided leaf, and decision-independently for the governing root
     * (the ADR 0032 memo); a no-op outside a web request.
     */
    void put(String resourceType, String resourceId, Object resource);
}
