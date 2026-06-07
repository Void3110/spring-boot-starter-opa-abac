package dev.dmitriikonovalov.opaabac.data.model;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchyLabels;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * The <strong>opt-in</strong> persistent base for a resource that participates in a hierarchy. Extends
 * {@link AbstractSecuredEntity} and adds a denormalized materialized {@code ltree} {@code path} column that
 * encodes the resource's full lineage as dotted {@code <type>_<id>} labels
 * (e.g. {@code catalog_<hex>.category_<hex>.product_<hex>}). The {@code LtreeAncestorResolver} reads this
 * column in one indexed query to produce the ancestor chain.
 *
 * <p><b>Opt-in, zero-cost otherwise.</b> A non-hierarchical secured entity keeps extending
 * {@link AbstractSecuredEntity} and pays nothing — no {@code path} column, no behavior change. Only an
 * entity that genuinely forms a tree extends this base; the whole cost of being hierarchical is "extend
 * this base + declare your immediate parent via {@link #abacParent()}."
 *
 * <p><b>Product's missing {@code catalogId}.</b> A Product carries only {@code categoryId}, yet its
 * {@code path} encodes the full {@code catalog → category → product} lineage — so the resolver can return
 * the Catalog ancestor without a redundant foreign key. The path is the single denormalized lineage.
 *
 * <p><b>Path maintenance.</b> The {@code path} is derived from the parent's path on insert/update and
 * rewritten atomically on a re-parent — that mechanism lives in
 * {@link dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalPathMaintainer}, not on the entity, so the
 * write stays centralized (the same posture as {@code AbstractCrudService.mutate}). The {@code path} column
 * itself is written through a {@code ?::ltree} cast so a bound {@link String} lands in the {@code ltree}
 * column without relying on an implicit cast.
 */
@MappedSuperclass
public abstract class AbstractHierarchicalEntity extends AbstractSecuredEntity {

    /**
     * The materialized lineage path (an {@code ltree}). Stored/read as text; the write cast
     * ({@code ?::ltree}) keeps Hibernate's {@link String} binding compatible with the {@code ltree} column.
     */
    @Column(name = "path", columnDefinition = "ltree")
    @ColumnTransformer(write = "?::ltree")
    private String path;

    protected AbstractHierarchicalEntity() {
        // JPA
    }

    protected AbstractHierarchicalEntity(UUID id) {
        super(id);
    }

    /**
     * This resource's <em>immediate</em> parent — the one declarative hop the path-maintainer and the
     * resolver build on. A root resource returns {@link Optional#empty()}. Each concrete hierarchical
     * entity implements this (e.g. a Product returns its Category; a nested Category returns its parent
     * Category; a root Category returns its Catalog).
     */
    @Override
    public abstract Optional<ParentRef> abacParent();

    /** The current materialized path, or {@code null} before it has been assigned. */
    public String getPath() {
        return path;
    }

    /** Set by the path-maintainer; not part of the public domain API. */
    public void setPath(String path) {
        this.path = path;
    }

    /** This resource's own {@code ltree} label ({@code <type>_<id>}) — the last segment of its path. */
    public String selfLabel() {
        return HierarchyLabels.label(abacResourceType(), abacResourceId());
    }
}
