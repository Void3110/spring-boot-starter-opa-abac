package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractSecuredEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A category within a catalog. Self-referencing via {@code parentId} to form a tree;
 * a null parent is a root category. (Kept as a plain id reference rather than a JPA
 * association to keep the hierarchy explicit and easy to reason about for the ABAC demo.)
 *
 * <p>Extends the secure base — authorizable as resource type {@code "category"}, with audit
 * columns, version, and tags inherited.
 */
@Entity
@Table(name = "category")
public class CategoryEntity extends AbstractSecuredEntity {

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
