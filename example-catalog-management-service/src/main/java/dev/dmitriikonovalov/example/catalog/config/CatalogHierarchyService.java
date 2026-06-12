package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.VersionConflictException;
import dev.dmitriikonovalov.opaabac.core.VersionGuard;
import dev.dmitriikonovalov.opaabac.core.Versioned;
import dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalPathMaintainer;
import dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The app-side path-maintenance flows for the catalog hierarchy (Phase 5.5-A): assign a row's {@code ltree}
 * path on create from its parent, and atomically re-parent a Category subtree. Delegates the algorithm to
 * the library {@link HierarchicalPathMaintainer}; this service just supplies the catalog domain glue (the
 * table name, the persisted entity).
 */
@Service
public class CatalogHierarchyService {

    private final HierarchicalPathMaintainer maintainer;
    private final CategoryRepository categories;

    @PersistenceContext
    private EntityManager entityManager;

    public CatalogHierarchyService(HierarchicalPathMaintainer maintainer, CategoryRepository categories) {
        this.maintainer = maintainer;
        this.categories = categories;
    }

    /**
     * Create a hierarchical row with its path derived from the declared parent — path read, derivation
     * and INSERT in <strong>one transaction</strong>, with the parent row locked {@code FOR UPDATE}
     * first. The lock is what makes the path read decision-safe (CONCURRENCY-AND-LOCKING.md Rule 1): a
     * concurrent re-parent of the parent serializes against it, so the child lands either under the
     * parent's old branch (and is swept by the move's subtree rewrite) or under the new one — never
     * under a branch that no longer exists.
     *
     * @param entity the new, unsaved entity (parent declared via {@code abacParent()})
     * @param save   the repository save, e.g. {@code categories::save}
     */
    @Transactional
    public <T extends AbstractHierarchicalEntity> T createWithPath(T entity, UnaryOperator<T> save) {
        entity.abacParent().ifPresent(this::lockParentRow);
        maintainer.assignPath(entity);
        return save.apply(entity);
    }

    /**
     * Derive and set the entity's path from its declared parent before it is persisted. Must be called
     * within the same transaction as the save so the parent's path is visible and stable — production
     * create flows go through {@link #createWithPath} (which also locks the parent); this remains for
     * callers already inside one transaction (test seeding).
     */
    @Transactional
    public <T extends AbstractHierarchicalEntity> T assignPath(T entity) {
        maintainer.assignPath(entity);
        return entity;
    }

    /** Lock the declared parent's row for the rest of the transaction (whitelisted table per type). */
    private void lockParentRow(ParentRef parent) {
        String table = switch (parent.type()) {
            case "catalog" -> "catalog";
            case "category" -> "category";
            default -> throw new IllegalArgumentException("unknown parent type: " + parent.type());
        };
        // An empty result means a missing parent — assignPath then throws the broken-lineage error.
        entityManager
                .createNativeQuery("SELECT id FROM " + table + " WHERE id = CAST(? AS uuid) FOR UPDATE")
                .setParameter(1, parent.id())
                .getResultList();
    }

    /**
     * Atomically re-parent a Category subtree under a new parent (another Category, or the Catalog root when
     * {@code newParentCategoryId} is {@code null}): rewrite the moved subtree's paths AND update the
     * {@code parentId} in the same transaction. Fail-closed if the rewrite cannot complete.
     *
     * <p>{@code decisionSnapshot} is the instance the caller's authorization decisions and delta
     * dispatch were computed on. The moving row is required to STILL carry that version once the
     * {@code FOR UPDATE} lock is held — drift means a parallel writer won the window between the
     * caller's read and this lock (a window that contains the caller's slow tag-validation call), and
     * the answer is {@code 409} via {@link VersionConflictException}, never a silent overwrite
     * (CONCURRENCY-AND-LOCKING.md Rules 1–2). Without it, the fresh re-read returned below would
     * absorb the racer's version and the caller's subsequent save would overwrite the racer's write —
     * including across the Phase-6.5 WRITE/TAG boundary the delta dispatch decided on.
     *
     * @return the moved Category, with its new path loaded
     */
    @Transactional
    public CategoryEntity reparentCategory(UUID categoryId, UUID newParentCategoryId, Versioned decisionSnapshot) {
        // Lock the moving Category AND the new-parent Category FOR UPDATE, in deterministic id order
        // (deadlock avoidance), as the first entity-touching reads. Locking only the moving row is not
        // enough: the cycle guard + new-parent path (step 3) are DECISIONS, and they must be computed
        // under the same locks that hold through the rewrite (CONCURRENCY-AND-LOCKING.md Rule 1) —
        // otherwise two crossing re-parents (A→B, B→A) each pass the cycle check against the other's
        // pre-move path and commit a cycle (retro-audit 2026-06-12).
        List<UUID> lockOrder = newParentCategoryId == null || newParentCategoryId.equals(categoryId)
                ? List.of(categoryId)
                : Stream.of(categoryId, newParentCategoryId).sorted().toList();
        CategoryEntity category = null;
        for (UUID id : lockOrder) {
            CategoryEntity locked = categories.findByIdForUpdate(id).orElseThrow(
                    () -> new IllegalArgumentException("Category not found: " + id));
            if (id.equals(categoryId)) {
                category = locked;
            }
        }
        // Bind the caller's decision basis to the locked row BEFORE any write: the version the
        // deltas/gates decided on must be the version the lock now holds (drift -> 409).
        VersionGuard.requireUnchanged(decisionSnapshot, category);
        String oldPath = category.getPath();

        Optional<ParentRef> newParent = newParentCategoryId == null
                ? Optional.of(new ParentRef("catalog", category.getCatalogId().toString()))
                : Optional.of(new ParentRef("category", newParentCategoryId.toString()));

        // 1) Update the adjacency (parentId) on the managed entity and flush it. (Done first so the entity's
        //    stale in-memory `path` can't later overwrite the native rewrite when Hibernate flushes.)
        category.setParentId(newParentCategoryId);
        categories.saveAndFlush(category);

        // 2) Detach all managed entities, so the subsequent native path rewrite is the source of truth and
        //    no stale managed `path` is flushed back over it.
        entityManager.clear();

        // 3) Rewrite the moved subtree's `path` columns atomically (validates the new parent + rejects a
        //    cycle). The catalog hierarchy spans TWO tables — category and product — so the descendant
        //    Products under the moved Categories must be rewritten too (a single-table UPDATE can't reach
        //    them). Both run in the SAME transaction; the first call validates the new parent + cycle.
        maintainer.reparent("category", oldPath, newParent);
        maintainer.reparentDescendantsInTable("product", oldPath, newParent);

        // 4) Re-read fresh (the persistence context was cleared), so the returned entity has the new path.
        return categories.findById(categoryId).orElseThrow();
    }
}
