package dev.dmitriikonovalov.opaabac.core;

import java.util.Optional;

/**
 * A request-scoped, write-through cache of resources the gate has <em>authorized</em>: the
 * authorization manager {@code put}s the resolved instance after an <strong>allow</strong>, and the
 * handler (or a later read-side consumer, e.g. action enrichment) {@code get}s it back instead of
 * issuing a second load — the response is then exactly the state the decision saw.
 *
 * <p><strong>An attribute snapshot, never a verdict.</strong> A cached entry is the <em>resolved
 * resource</em> (its attributes — tags, hierarchy) as it was when written; <em>presence is not an
 * authorization</em>. Every per-action decision — gate or affordance enrichment — is computed fresh
 * against this snapshot; an entry never short-circuits a verdict to "allowed".
 *
 * <p><strong>Never consulted by decisions.</strong> The gate populates this cache but never reads it:
 * every evaluation resolves fresh, so a cached instance can never widen (or stale-out) a decision. A
 * deny puts nothing. Entries are request-bounded — nothing survives the request, and outside a web
 * request the default implementation degrades to a no-op (resolution still feeds the decision; only
 * the reuse is lost).
 *
 * <p>This interface is framework-agnostic (it lives in {@code opa-abac-core}); the default
 * request-attributes implementation lives in {@code opa-abac-spring-security}.
 */
public interface AbacResourceCache {

    /**
     * Look up the authorized instance cached for this request, if any.
     *
     * @param resourceType the resource type the gate authorized
     * @param resourceId   the resource id the gate authorized
     * @param as           the expected type; a cached instance of a different type yields empty
     * @return the instance the decision was made on, or empty (not cached / wrong type / no request)
     */
    <T> Optional<T> get(String resourceType, String resourceId, Class<T> as);

    /**
     * Cache an authorized instance for the remainder of this request. Called by the authorization
     * manager on allow; a no-op outside a web request.
     */
    void put(String resourceType, String resourceId, Object resource);
}
