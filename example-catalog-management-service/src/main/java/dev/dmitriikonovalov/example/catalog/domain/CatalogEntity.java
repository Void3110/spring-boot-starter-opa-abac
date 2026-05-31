package dev.dmitriikonovalov.example.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;

@Entity
@Table(name = "catalog")
public class CatalogEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    // Liquibase creates this column as `timestamptz`; NATIVE storage makes Hibernate
    // expect (and use) a real `timestamp with time zone` rather than UTC-normalized timestamp.
    @Column(name = "created_at", nullable = false, updatable = false)
    @TimeZoneStorage(TimeZoneStorageType.NATIVE)
    private OffsetDateTime createdAt;

    protected CatalogEntity() {
        // JPA
    }

    public CatalogEntity(UUID id, String name, String description, OffsetDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
