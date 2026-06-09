package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Resolves the <strong>ancestor chain</strong> of a leaf resource — the SPI behind N-level hierarchical
 * authorization. Given a leaf {@code (type, id)}, it walks the resource hierarchy up to the root and returns
 * the chain that travels to OPA as {@code input.resource.ancestors}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Root-first, leaf-excluded.</b> The returned list is ordered from the root down to (but not
 *       including) the leaf — the leaf is already the decision's {@code resource.type}/{@code id}. For
 *       {@code catalog → category → product}, resolving the product yields
 *       {@code [ (catalog, …), (category, …) ]}.</li>
 *   <li><b>Empty when there is no inheritable lineage.</b> A root resource (or a resource whose type
 *       declares no parent) yields an empty list — not an error.</li>
 *   <li><b>Fail-closed: throw, never truncate.</b> A cycle, a broken parent link, a depth-bound breach, a
 *       malformed/{@code NULL} lineage, or any SQL error MUST raise {@link AncestorResolutionException}.
 *       A resolver must never return a partial chain — callers rely on "throw ⇒ no inheritance ⇒ direct
 *       grant only," so a silent truncation could mis-grant.</li>
 * </ul>
 *
 * <p>Two interchangeable implementations ship: {@link LtreeAncestorResolver} (reads a denormalized
 * materialized {@code ltree} path in one indexed query — the default) and {@link RecursiveCteAncestorResolver}
 * (walks the live parent linkage, correct-by-construction on re-parent). The app picks one per its
 * re-parent frequency; both honor a configurable depth bound and detect cycles.
 */
public interface AncestorResolver {

    /**
     * The root-first, leaf-excluded ancestor chain for the given leaf.
     *
     * @param leafType the leaf resource's ABAC type (e.g. {@code "product"})
     * @param leafId   the leaf resource's id
     * @return the ancestors from root to the leaf's immediate parent; empty when the leaf has no
     *     inheritable lineage; never {@code null}
     * @throws AncestorResolutionException on a cycle, broken link, depth breach, malformed lineage, or SQL
     *     error — the fail-closed signal (callers treat it as "no inheritance," never "allow")
     */
    List<ParentRef> ancestorsOf(String leafType, String leafId);

    /**
     * A JPA {@link Specification} selecting the rows in the <strong>subtree rooted at</strong>
     * {@code (rootType, rootId)} — the <em>inverse</em> of {@link #ancestorsOf}: where {@code ancestorsOf}
     * walks <em>up</em> from a leaf, this selects everything <em>below</em> (and including) a root. It is the
     * lineage predicate Slice 5.5-B OR-s into a list query so an inheritable grant on the governing root
     * widens which rows the list returns (the hierarchy-aware list filter).
     *
     * <h2>Contract</h2>
     * <ul>
     *   <li><b>Subtree-of, root-inclusive.</b> The predicate matches the root row itself and every descendant
     *       beneath it. A list scoped to a single child type (e.g. {@code category} under a {@code catalog})
     *       sees only its own type's rows, because the caller AND-s the result with the entity's own table —
     *       the predicate is over the entity's lineage column, not a join.</li>
     *   <li><b>SQL pushdown, no id materialization where possible.</b> The {@code ltree} impl returns a single
     *       {@code path <@ '<root-label>'} predicate — the descendant id set is never enumerated in Java. The
     *       CTE impl materializes the bounded descendant id set and returns an {@code id IN (…)} predicate.</li>
     *   <li><b>Fail-closed: an always-false predicate, never the whole table.</b> A depth breach, a cycle, a
     *       missing/malformed root path, or any SQL error yields a predicate matching <em>no</em> row
     *       (an empty {@code subtreeSpec}), so the list falls back to the <strong>narrower</strong> tag-only
     *       result — never wider. Unlike {@link #ancestorsOf}, this method does <em>not</em> throw: it
     *       collapses to the empty predicate, because the caller composes it as "OR these subtree rows in,"
     *       and a thrown exception there would be harder to keep fail-closed than a no-op widening.</li>
     * </ul>
     *
     * @param rootType the subtree root's ABAC type (e.g. {@code "catalog"})
     * @param rootId   the subtree root's id
     * @param <T>      the queried entity type (an {@link dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity})
     * @return a {@link Specification} matching the root's subtree; an always-false predicate on any breach
     *     (fail-closed); never {@code null}
     */
    <T> Specification<T> subtreeOf(String rootType, String rootId);
}
