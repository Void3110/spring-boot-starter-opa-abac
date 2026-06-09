package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.List;

/**
 * Supplies a single resource's <em>immediate children</em> from the live adjacency linkage — the
 * <strong>downward</strong> counterpart of {@link ParentLinkSource}, for {@link RecursiveCteAncestorResolver}'s
 * {@link AncestorResolver#subtreeOf(String, String) subtreeOf}. Where {@link ParentLinkSource} climbs
 * {@code parent_id} <em>up</em> to resolve an ancestor chain, this walks {@code parent_id} <em>down</em> to
 * enumerate a subtree.
 *
 * <p>An implementation typically backs this with a query like
 * {@code SELECT id FROM <child-table> WHERE parent_id = ?} per child table (or a single recursive CTE the
 * resolver drives one level at a time). Each child is returned as a {@link ParentRef} {@code (childType,
 * childId)} so the resolver can recurse into the next level (a category's children may themselves be
 * categories <em>or</em> products). The resolver composes the levels into the full descendant set and applies
 * the cycle and depth guards on top — so an implementation does <em>not</em> need to guard against cycles
 * itself.
 *
 * <p>Returning an empty list means "this resource is a leaf / has no children," which ends that branch of the
 * walk cleanly. A SQL error should propagate; the resolver collapses {@code subtreeOf} fail-closed (an
 * always-false predicate) on any error.
 *
 * <p>This source is <strong>optional</strong>: an app that only needs single-resource hierarchy (Slice 5.5-A)
 * or uses the {@code ltree} resolver does not supply it. A CTE resolver wired without it answers
 * {@code subtreeOf} with the fail-closed empty predicate (so a list simply does not widen via the CTE path),
 * which is safe — never wider.
 */
@FunctionalInterface
public interface DescendantIdSource {

    /**
     * The immediate children of {@code (type, id)} across the hierarchy (any child type), from the live
     * {@code parent_id} adjacency.
     *
     * @param type the parent resource's ABAC type
     * @param id   the parent resource's id
     * @return the immediate children as {@code (childType, childId)} refs; empty when the resource is a leaf;
     *     never {@code null}
     */
    List<ParentRef> childrenOf(String type, String id);
}
