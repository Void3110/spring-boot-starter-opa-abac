package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalPathMaintainer;
import dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
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
     * Derive and set the entity's path from its declared parent before it is persisted. For a root (a
     * Catalog) the path is just its own label; otherwise {@code parent.path || self-label}. Must be called
     * within the same transaction as the save so the parent's path is visible.
     */
    @Transactional
    public <T extends AbstractHierarchicalEntity> T assignPath(T entity) {
        maintainer.assignPath(entity);
        return entity;
    }

    /**
     * Atomically re-parent a Category subtree under a new parent (another Category, or the Catalog root when
     * {@code newParentCategoryId} is {@code null}): rewrite the moved subtree's paths AND update the
     * {@code parentId} in the same transaction. Fail-closed if the rewrite cannot complete.
     *
     * @return the moved Category, with its new path loaded
     */
    @Transactional
    public CategoryEntity reparentCategory(UUID categoryId, UUID newParentCategoryId) {
        // Lock the moving Category FOR UPDATE as the first entity-touching read (the repo's lock-first
        // mutate posture, AbstractCrudService.mutate): two concurrent re-parents of the same Category then
        // serialize at the row lock instead of racing on a stale @Version. The subtree path rewrite (step 3)
        // happens in this same transaction.
        CategoryEntity category = categories.findByIdForUpdate(categoryId).orElseThrow(
                () -> new IllegalArgumentException("Category not found: " + categoryId));
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
