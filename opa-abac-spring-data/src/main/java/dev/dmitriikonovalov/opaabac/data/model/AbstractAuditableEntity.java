package dev.dmitriikonovalov.opaabac.data.model;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The plain persistent base: a {@link UUID} identity, audit bookkeeping (who/when created and last
 * modified), and an optimistic-lock {@link Version}. No tags — entities that need authorization
 * attributes extend {@link AbstractSecuredEntity} instead, so non-secured entities don't pay for a
 * tag column.
 *
 * <p>Auditing is populated by Spring Data ({@link AuditingEntityListener}) from the application's
 * {@code AuditorAware<UUID>} bean; the timestamps use {@link OffsetDateTime} with NATIVE timezone
 * storage so they map to Postgres {@code timestamptz}.
 *
 * <p>The id is <em>client-supplied</em> ({@code updatable = false}); this base imposes no generation
 * strategy.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AbstractAuditableEntity implements BaseModel<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @LastModifiedBy
    @Column(name = "last_modified_by")
    private UUID lastModifiedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    @TimeZoneStorage(TimeZoneStorageType.NATIVE)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "last_modified_at")
    @TimeZoneStorage(TimeZoneStorageType.NATIVE)
    private OffsetDateTime lastModifiedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected AbstractAuditableEntity() {
        // JPA
    }

    protected AbstractAuditableEntity(UUID id) {
        this.id = id;
    }

    @Override
    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getLastModifiedBy() {
        return lastModifiedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastModifiedAt() {
        return lastModifiedAt;
    }

    @Override
    public Integer getVersion() {
        return version;
    }

    /**
     * Identity is by {@code id} only and proxy-safe: a Hibernate lazy proxy compares equal to its
     * loaded entity because we compare the real classes via {@link Hibernate#getClass(Object)}, and
     * we never use mutable fields. Two as-yet-unsaved instances (null id) are equal only by reference.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        AbstractAuditableEntity other = (AbstractAuditableEntity) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        // Class-based and constant for an instance: stable across the null-id -> assigned-id
        // transition (so an entity stays findable in a hash set after persist) and identical for an
        // entity and its Hibernate proxy.
        return Hibernate.getClass(this).hashCode();
    }
}
