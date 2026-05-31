package dev.dmitriikonovalov.example.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "product")
public class ProductEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

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
        this.id = id;
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.sku = sku;
        this.priceCents = priceCents;
        this.currency = currency;
    }

    public UUID getId() {
        return id;
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
