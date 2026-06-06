package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A product within a category. Extends the <b>hierarchical</b> secure base (Phase 5.5-A) — authorizable as
 * resource type {@code "product"}, carrying the denormalized {@code ltree} {@code path}. Its immediate
 * parent is its Category; the path encodes the full {@code catalog → category → product} lineage, so a
 * Catalog grant can govern a Product even though a Product carries no {@code catalogId}.
 */
@Entity
@Table(name = "product")
public class ProductEntity extends AbstractHierarchicalEntity {

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column
    private String sku;

    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    @Column(nullable = false, length = 3)
    private String currency;

    protected ProductEntity() {
        // JPA
    }

    public ProductEntity(UUID id, UUID categoryId, String name, String description,
                         String sku, Long priceCents, String currency) {
        super(id);
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.sku = sku;
        this.priceCents = priceCents;
        this.currency = currency;
    }

    @Override
    public String abacResourceType() {
        return "product";
    }

    /** The immediate parent for the ancestor walk: this Product's Category. */
    @Override
    public Optional<ParentRef> abacParent() {
        return Optional.of(new ParentRef("category", categoryId.toString()));
    }

    /**
     * Tags plus the intrinsic {@code categoryId}, so a policy can authorize a product relative to its
     * category (e.g. hierarchical inheritance) without a separate lookup.
     */
    @Override
    public Map<String, Object> abacAttributes() {
        Map<String, Object> attributes = new HashMap<>(getTags().asMap());
        if (categoryId != null) {
            attributes.put("categoryId", categoryId.toString());
        }
        return attributes;
    }

    public UUID getCategoryId() {
        return categoryId;
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

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Long getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(Long priceCents) {
        this.priceCents = priceCents;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
