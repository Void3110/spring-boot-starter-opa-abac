package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Optional;
import java.util.UUID;

/**
 * A product catalog — the <b>root</b> of the resource hierarchy. Extends the hierarchical secure base
 * (Phase 5.5-A) so it carries the {@code ltree} {@code path} (just its own label, as a root) that the
 * Categories and Products beneath it build their lineage on. Authorizable as resource type
 * {@code "catalog"}; has no parent ({@link #abacParent()} empty).
 */
@Entity
@Table(name = "catalog")
public class CatalogEntity extends AbstractHierarchicalEntity {

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    protected CatalogEntity() {
        // JPA
    }

    public CatalogEntity(UUID id, String name, String description) {
        super(id);
        this.name = name;
        this.description = description;
    }

    @Override
    public String abacResourceType() {
        return "catalog";
    }

    /** A Catalog is the hierarchy root — it has no parent. */
    @Override
    public Optional<ParentRef> abacParent() {
        return Optional.empty();
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
