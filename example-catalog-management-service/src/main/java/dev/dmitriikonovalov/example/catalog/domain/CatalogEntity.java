package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractSecuredEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A product catalog — the root of the resource hierarchy. Extends the secure base, so it carries
 * audit columns, an optimistic-lock version, and ABAC tags, and is authorizable as resource type
 * {@code "catalog"}. The id/created-at/version/tags are inherited; {@code createdAt} is now
 * populated by Spring Data auditing rather than supplied by the caller.
 */
@Entity
@Table(name = "catalog")
public class CatalogEntity extends AbstractSecuredEntity {

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
