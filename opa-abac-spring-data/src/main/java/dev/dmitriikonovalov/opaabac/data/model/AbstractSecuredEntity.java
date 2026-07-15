package dev.dmitriikonovalov.opaabac.data.model;

import dev.dmitriikonovalov.opaabac.core.AbacResource;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.MappedSuperclass;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The authorizable persistent base. Extends {@link AbstractAuditableEntity} and adds the two
 * things that make a domain object policy-aware:
 *
 * <ol>
 *   <li><b>Tags</b> — a JSONB {@code tags} column backed by {@link ResourceTags}, the resource-side
 *       attributes an OPA policy reads.</li>
 *   <li><b>{@link AbacResource}</b> — so the framework can build an authorization context resource
 *       from any secured entity with no per-entity glue.</li>
 * </ol>
 *
 * The whole cost of making a domain object authorizable is "extend {@code AbstractSecuredEntity} and
 * declare your {@link #abacResourceType() resource type}." This is the deliberate fix for the
 * "secure is just a marker" pattern: here, secure means <em>carries attributes and is authorizable</em>.
 */
@MappedSuperclass
public abstract class AbstractSecuredEntity extends AbstractAuditableEntity
        implements Taggable, AbacResource {

    @Convert(converter = ResourceTagsConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb", nullable = false)
    private ResourceTags tags = ResourceTags.empty();

    protected AbstractSecuredEntity() {
        // JPA
    }

    protected AbstractSecuredEntity(UUID id) {
        super(id);
    }

    @Override
    public ResourceTags getTags() {
        return tags;
    }

    @Override
    public void setTags(ResourceTags tags) {
        this.tags = tags == null ? ResourceTags.empty() : tags;
    }

    // abacResourceType() is abstract in AbacResource (no default) — each concrete entity declares its
    // type, e.g. "catalog"/"product". Not re-declared here: that would only duplicate the interface.

    @Override
    public String abacResourceId() {
        return getId() == null ? null : getId().toString();
    }

    /**
     * The resource attributes an OPA policy evaluates against: the tags by default. Override to merge
     * intrinsic columns a policy may care about (e.g. a product's {@code categoryId}).
     */
    @Override
    public Map<String, Object> abacAttributes() {
        return tags.asMap();
    }
}
