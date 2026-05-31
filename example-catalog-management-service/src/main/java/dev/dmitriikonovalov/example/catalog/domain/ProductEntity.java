package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractSecuredEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A product within a category. Extends the secure base — authorizable as resource type
 * {@code "product"}, with audit columns, version, and tags inherited.
 */
@Entity
@Table(name = "product")
public class ProductEntity extends AbstractSecuredEntity {

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
