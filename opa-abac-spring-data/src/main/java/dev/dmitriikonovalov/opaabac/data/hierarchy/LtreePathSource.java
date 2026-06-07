package dev.dmitriikonovalov.opaabac.data.hierarchy;

import java.util.Optional;

/**
 * Supplies the denormalized materialized {@code ltree} path of a single resource, for
 * {@link LtreeAncestorResolver}. The path encodes the full lineage as dotted {@code <type>_<id>} labels,
 * e.g. {@code catalog_1.category_7.product_42}.
 *
 * <p>This is the per-application data-access seam the library does not own: an app maps a resource
 * {@code (type, id)} to the row that holds its {@code path} column (typically by reading the
 * {@link AbstractHierarchicalEntity}'s {@code path}). The resolver owns the fail-closed <em>walk</em>
 * (decode, order, leaf-exclude, depth-bound); this source owns only the <em>lookup</em>.
 *
 * <p>Returning {@link Optional#empty()} means "no such resource / no path" — the resolver decides what that
 * means for the walk (a missing path for a leaf that should have one is a broken lineage → throw). A
 * lookup that hits a SQL error should let that error propagate; the resolver wraps it fail-closed.
 */
@FunctionalInterface
public interface LtreePathSource {

    /**
     * The raw materialized path for {@code (type, id)}, or empty when the resource has no path row.
     *
     * @param type the resource's ABAC type
     * @param id   the resource's id
     * @return the dotted {@code ltree} path (e.g. {@code catalog_1.category_7.product_42}), or empty
     */
    Optional<String> pathOf(String type, String id);
}
