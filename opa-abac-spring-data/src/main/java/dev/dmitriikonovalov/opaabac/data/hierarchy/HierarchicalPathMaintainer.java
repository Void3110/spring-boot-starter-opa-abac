package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the denormalized {@code ltree} {@code path} on an {@link AbstractHierarchicalEntity}: it derives
 * the path from the parent on insert/update, and rewrites a moved subtree's paths <strong>atomically</strong>
 * on a re-parent. This is the library's path-maintenance mechanism — kept off the entity so the write stays
 * centralized (the same posture as {@code AbstractCrudService.mutate}).
 *
 * <h2>Insert/update: {@link #assignPath}</h2>
 * The path is {@code parent.path || self-label} (or just {@code self-label} for a root). The parent's path
 * is read via the supplied {@link LtreePathSource}; a declared-but-missing parent path is a broken lineage
 * and throws (fail-closed) rather than silently producing a root path.
 *
 * <h2>Re-parent: {@link #reparent}</h2>
 * Moving a node under a new parent must rewrite the {@code path} of the <em>entire moved subtree</em> in the
 * <strong>same transaction</strong> as the parent change — a concurrent decision must never see a
 * half-rewritten tree. A single {@code ltree} UPDATE does the whole subtree:
 *
 * <pre>{@code
 * UPDATE <table>
 *    SET path = <newParentPath> || subpath(path, nlevel(<oldSelfPath>) - 1)
 *  WHERE path <@ <oldSelfPath>
 * }</pre>
 *
 * {@code <@} matches the moved node and every descendant; {@code subpath(path, nlevel(oldSelfPath)-1)} keeps
 * the suffix from the moved node's own label onward, so a {@code catalogA.catX.prodY} subtree moved under
 * {@code catalogB.catZ} becomes {@code catalogB.catZ.catX.prodY}. Because it is one statement in one
 * transaction it is atomic: it commits wholly or rolls back wholly (fail-closed — no partial rewrite).
 */
public class HierarchicalPathMaintainer {

    private final EntityManager entityManager;
    private final LtreePathSource pathSource;

    public HierarchicalPathMaintainer(EntityManager entityManager, LtreePathSource pathSource) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.pathSource = Objects.requireNonNull(pathSource, "pathSource");
    }

    /**
     * Derive and set {@code entity.path} from its declared parent. For a root ({@code abacParent()} empty)
     * the path is just the self-label; otherwise it is {@code parentPath + "." + selfLabel}.
     *
     * @throws AncestorResolutionException if a declared parent has no resolvable path (broken lineage)
     */
    public void assignPath(AbstractHierarchicalEntity entity) {
        Objects.requireNonNull(entity, "entity");
        String selfLabel = entity.selfLabel();
        Optional<ParentRef> parent = entity.abacParent();
        if (parent.isEmpty()) {
            entity.setPath(selfLabel);
            return;
        }
        ParentRef p = parent.get();
        String parentPath = pathSource.pathOf(p.type(), p.id())
                .filter(path -> !path.isBlank())
                .orElseThrow(() -> new AncestorResolutionException(
                        "cannot assign path: parent " + p.type() + ":" + p.id() + " has no path (broken lineage)"));
        entity.setPath(parentPath + "." + selfLabel);
    }

    /**
     * Atomically re-parent a node: rewrite the {@code path} of the entire moved subtree to sit under
     * {@code newParent}, in one transaction. The caller is responsible for updating the entity's own
     * adjacency field (e.g. {@code parentId}) within the same transaction — pass that mutation via the
     * surrounding service so both land together.
     *
     * @param table        the physical table holding the {@code path} column (the entity's {@code @Table})
     * @param oldSelfPath  the moving node's current path (its subtree root)
     * @param newParent    the new parent reference; {@link Optional#empty()} moves the node to a root
     * @return the number of rows (the moved node + its descendants) whose path was rewritten
     * @throws AncestorResolutionException on a missing new-parent path or if nothing was rewritten
     */
    @Transactional
    public int reparent(String table, String oldSelfPath, Optional<ParentRef> newParent) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(oldSelfPath, "oldSelfPath");
        if (oldSelfPath.isBlank()) {
            throw new AncestorResolutionException("cannot re-parent: missing current path");
        }
        assertSafeIdentifier(table);

        // self-label is the last segment of the old path
        int lastDot = oldSelfPath.lastIndexOf('.');
        String selfLabel = lastDot < 0 ? oldSelfPath : oldSelfPath.substring(lastDot + 1);

        String newSelfPath;
        if (newParent.isEmpty()) {
            newSelfPath = selfLabel; // moved to a root
        } else {
            ParentRef np = newParent.get();
            String newParentPath = pathSource.pathOf(np.type(), np.id())
                    .filter(path -> !path.isBlank())
                    .orElseThrow(() -> new AncestorResolutionException(
                            "cannot re-parent under " + np.type() + ":" + np.id() + " (no path)"));
            // Guard against re-parenting a node under its own descendant (would form a cycle).
            if (isDescendantOrSelf(newParentPath, oldSelfPath)) {
                throw new AncestorResolutionException(
                        "cannot re-parent " + oldSelfPath + " under its own descendant " + newParentPath);
            }
            newSelfPath = newParentPath + "." + selfLabel;
        }

        // One atomic statement rewrites the whole subtree (moved node + descendants):
        //   new path := <newSelfPath> || <descendant suffix below the moved node>
        // The descendant suffix is the labels of `path` after the first `oldDepth` of them. For the moved
        // node itself nlevel(path) == oldDepth, and ltree's subpath(path, oldDepth) is an INVALID position
        // (offset must be < nlevel) — so a CASE handles the node itself (suffix empty → just newSelfPath)
        // separately from its descendants (nlevel > oldDepth → subpath is valid).
        int oldDepth = oldSelfPath.split("\\.").length;
        String sql = "UPDATE " + table
                + " SET path = CASE WHEN nlevel(path) = ?"
                + "                  THEN CAST(? AS ltree)"
                + "                  ELSE CAST(? AS ltree) || subpath(path, ?) END"
                + " WHERE path <@ CAST(? AS ltree)";
        int rewritten = entityManager.createNativeQuery(sql)
                .setParameter(1, oldDepth)
                .setParameter(2, newSelfPath)
                .setParameter(3, newSelfPath)
                .setParameter(4, oldDepth)
                .setParameter(5, oldSelfPath)
                .executeUpdate();

        if (rewritten < 1) {
            // The subtree root must exist; rewriting zero rows means the tree was not in the expected state.
            throw new AncestorResolutionException(
                    "re-parent rewrote no rows for subtree " + oldSelfPath + " (tree not in expected state)");
        }
        return rewritten;
    }

    private static boolean isDescendantOrSelf(String candidate, String ancestor) {
        return candidate.equals(ancestor) || candidate.startsWith(ancestor + ".");
    }

    /**
     * Defend the only string-interpolated part of the native query — the table name — against injection.
     * It must be a plain SQL identifier (optionally schema-qualified); never user input in practice (it is
     * the entity's {@code @Table}), but validated regardless.
     */
    private static void assertSafeIdentifier(String table) {
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
            throw new IllegalArgumentException("unsafe table identifier for re-parent: " + table);
        }
    }
}
