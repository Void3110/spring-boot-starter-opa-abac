package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Optional;
import java.util.UUID;

/**
 * A category within a catalog. Self-referencing via {@code parentId} to form a tree;
 * a null parent is a root category. (Kept as a plain id reference rather than a JPA
 * association to keep the hierarchy explicit and easy to reason about for the ABAC demo.)
 *
 * <p>Extends the <b>hierarchical</b> secure base (Phase 5.5-A) — authorizable as resource type
 * {@code "category"}, carrying the denormalized {@code ltree} {@code path} so an ancestor grant can
 * govern it. Its immediate parent is the parent Category when nested, else the governing Catalog.
 */
@Entity
@Table(name = "category")
public class CategoryEntity extends AbstractHierarchicalEntity {

    @Column(name = "catalog_id", nullable = false, updatable = false)
    private UUID catalogId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    protected CategoryEntity() {
        // JPA
    }

    public CategoryEntity(UUID id, UUID catalogId, UUID parentId, String name, String description) {
        super(id);
        this.catalogId = catalogId;
        this.parentId = parentId;
        this.name = name;
        this.description = description;
    }

    @Override
    public String abacResourceType() {
        return "category";
    }

    /**
     * The immediate parent for the ancestor walk: the parent Category when this is nested, otherwise the
     * governing Catalog. A root category therefore inherits from its Catalog; a nested one from its parent
     * Category (whose own path encodes the Catalog above it).
     */
    @Override
    public Optional<ParentRef> abacParent() {
        if (parentId != null) {
            return Optional.of(new ParentRef("category", parentId.toString()));
        }
        return Optional.of(new ParentRef("catalog", catalogId.toString()));
    }

    public UUID getCatalogId() {
        return catalogId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
