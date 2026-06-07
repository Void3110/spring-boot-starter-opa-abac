package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.Optional;

/**
 * Supplies a resource's <em>immediate</em> parent from the live adjacency linkage, for
 * {@link RecursiveCteAncestorResolver}. This is the authoritative one-hop relation (the same fact
 * {@code AbacDataObject.abacParent()} declares), read from the database without any denormalized path
 * column — so it is correct-by-construction on a re-parent (no materialized state to rewrite).
 *
 * <p>An implementation typically backs this with a recursive CTE (or a per-hop query) that climbs the
 * {@code parent_id} linkage; the resolver composes the hops into the chain and applies cycle detection +
 * the depth bound. Returning {@link Optional#empty()} means "this resource is a root / has no parent,"
 * which ends the walk cleanly. A SQL error should propagate; the resolver wraps it fail-closed.
 */
@FunctionalInterface
public interface ParentLinkSource {

    /**
     * The immediate parent of {@code (type, id)}, or empty when the resource is a root.
     *
     * @param type the resource's ABAC type
     * @param id   the resource's id
     * @return the immediate {@link ParentRef}, or empty when there is no parent (root)
     */
    Optional<ParentRef> parentOf(String type, String id);
}
