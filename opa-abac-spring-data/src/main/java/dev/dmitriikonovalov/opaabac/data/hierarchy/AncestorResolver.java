package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.List;

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
}
