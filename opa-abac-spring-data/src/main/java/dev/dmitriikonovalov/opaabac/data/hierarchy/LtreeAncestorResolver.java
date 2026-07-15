package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.jpa.domain.Specification;

/**
 * The default {@link AncestorResolver}: reads the leaf's denormalized materialized {@code ltree} path
 * (via a {@link LtreePathSource}) and decodes it into the root-first, leaf-excluded ancestor chain in
 * <strong>one indexed query</strong>. Naturally acyclic when the path is maintained correctly (a path
 * cannot contain a cycle), so the dominant failure modes here are a malformed/{@code NULL} path or a
 * depth-bound breach — both throw.
 *
 * <p>A path like {@code catalog_<hex>.category_<hex>.product_<hex>} for a leaf product decodes to
 * {@code [ (catalog, …), (category, …) ]}: the leaf's own label (the last segment) is dropped (leaf-
 * exclusion), and the remaining labels are returned root-first.
 *
 * <h2>Fail-closed</h2>
 * <ul>
 *   <li>No path row for the leaf → {@link AncestorResolutionException} (a leaf that should be hierarchical
 *       but has no lineage is a broken state, not "no ancestors").</li>
 *   <li>A path whose final label doesn't match the requested leaf → throw (the row is inconsistent).</li>
 *   <li>A malformed label, or a depth (label count) exceeding {@code maxDepth} → throw.</li>
 *   <li>Any error from the source propagates wrapped as {@link AncestorResolutionException}.</li>
 * </ul>
 */
public class LtreeAncestorResolver implements AncestorResolver {

    private final LtreePathSource pathSource;
    private final int maxDepth;

    /**
     * @param pathSource the per-app lookup of a resource's materialized path
     * @param maxDepth   the maximum number of labels a path may have; a deeper path throws (mandatory bound)
     */
    public LtreeAncestorResolver(LtreePathSource pathSource, int maxDepth) {
        this.pathSource = Objects.requireNonNull(pathSource, "pathSource");
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, was " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    @Override
    public List<ParentRef> ancestorsOf(String leafType, String leafId) {
        Objects.requireNonNull(leafType, "leafType");
        Objects.requireNonNull(leafId, "leafId");

        String path;
        try {
            path = pathSource.pathOf(leafType, leafId)
                    .filter(p -> !p.isBlank())
                    .orElseThrow(() -> new AncestorResolutionException(
                            "no ltree path for " + leafType + ":" + leafId + " (broken lineage)"));
        } catch (AncestorResolutionException e) {
            throw e;
        } catch (RuntimeException e) {
            // A SQL/data-access error must fail closed, never silently widen.
            throw new AncestorResolutionException(
                    "ltree path lookup failed for " + leafType + ":" + leafId, e);
        }

        String[] labels = path.split("\\.");
        if (labels.length == 0) {
            throw new AncestorResolutionException("empty ltree path for " + leafType + ":" + leafId);
        }
        // The depth bound is on the full lineage length (including the leaf label).
        if (labels.length > maxDepth) {
            throw new AncestorResolutionException(
                    "ltree path depth " + labels.length + " exceeds maxDepth " + maxDepth
                            + " for " + leafType + ":" + leafId);
        }

        // Decode every label; the last must be the leaf itself (consistency check), then drop it.
        List<ParentRef> decoded = new ArrayList<>(labels.length);
        for (String labelToken : labels) {
            decoded.add(HierarchyLabels.decode(labelToken)); // throws on a malformed label
        }
        ParentRef leafLabel = decoded.get(decoded.size() - 1);
        if (!leafLabel.type().equals(leafType) || !leafLabel.id().equals(leafId)) {
            throw new AncestorResolutionException(
                    "ltree path leaf " + leafLabel + " does not match requested " + leafType + ":" + leafId);
        }

        // Root-first, leaf-excluded.
        return List.copyOf(decoded.subList(0, decoded.size() - 1));
    }

    /**
     * The subtree predicate as a pure {@code ltree} SQL pushdown: {@code path <@ '<root-path>'}, where the
     * root's materialized path is read once via the {@link LtreePathSource}. The descendant id set is
     * <strong>never materialized</strong> in Java — this is the whole point of the {@code ltree} strategy.
     *
     * <p>The predicate is over the <em>queried entity's</em> own {@code path} column (every
     * {@link AbstractHierarchicalEntity} carries one), so a list scoped to one child type matches only that
     * type's rows beneath the root. The literal root path is bound (no SQL-string interpolation) and cast to
     * {@code ltree} so the {@code <@} descendant operator applies.
     *
     * <h2>Fail-closed</h2>
     * A missing/blank root path, a path that exceeds {@code maxDepth} (a malformed lineage), or any
     * source/SQL error collapses to an <strong>always-false</strong> predicate — the list then falls back to
     * the narrower tag-only result, never the whole table.
     */
    @Override
    public <T> Specification<T> subtreeOf(String rootType, String rootId) {
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(rootId, "rootId");

        String rootPath;
        try {
            rootPath = pathSource.pathOf(rootType, rootId)
                    .filter(p -> !p.isBlank())
                    .orElse(null);
        } catch (RuntimeException _) {
            // A SQL/data-access error must fail closed (empty widening), never the whole table.
            rootPath = null;
        }
        if (rootPath == null) {
            return alwaysFalse();
        }
        // A path deeper than the bound is a malformed lineage → fail closed (no widening).
        if (rootPath.split("\\.").length > maxDepth) {
            return alwaysFalse();
        }

        final String boundRootPath = rootPath;
        // entity.path <@ rootPath  ≡  "is rootPath an ancestor of (or equal to) entity.path"
        //                          ≡  ltree_isparent(rootPath, entity.path)  — the function form of the
        // `@>` operator (the reverse of `<@`). Using the function keeps the predicate inside JPA Criteria
        // (no native-SQL operator string); the bound literal is cast to ltree via text2ltree(?).
        return (root, query, cb) ->
                cb.isTrue(cb.function(
                        "ltree_isparent",
                        Boolean.class,
                        cb.function("text2ltree", Object.class, cb.literal(boundRootPath)),
                        root.get("path")));
    }

    /** An always-false predicate — the fail-closed empty-widening shape (matches no row). */
    private static <T> Specification<T> alwaysFalse() {
        return (root, query, cb) -> cb.disjunction();
    }

    /** A convenience factory matching the SPI source shape. */
    public static LtreeAncestorResolver of(LtreePathSource pathSource, int maxDepth) {
        return new LtreeAncestorResolver(pathSource, maxDepth);
    }

    /** Exposed for the starter/tests to confirm the configured bound. */
    public int maxDepth() {
        return maxDepth;
    }
}
