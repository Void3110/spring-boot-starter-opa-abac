package dev.dmitriikonovalov.example.usermgmt.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the <b>dynamic tag dictionary</b> — the controlled vocabulary a resource tag may use. Where
 * the source platform fixes tag keys at compile time, a key here is a runtime-editable row, so a team can
 * define its own keys without a redeploy. Two kinds, mirroring {@link RoleDefinitionEntity}:
 *
 * <ul>
 *   <li><b>global system keys</b> — {@code system = true}, {@code scope = GLOBAL}, {@code teamId = null}:
 *       seeded by Liquibase, immutable through the API;</li>
 *   <li><b>team-scoped keys</b> — {@code system = false}, {@code scope = TEAM}, {@code teamId} set:
 *       owner/administrator-defined, live in the DB, scoped to one team.</li>
 * </ul>
 *
 * <p>A definition constrains a value's <em>legality</em> only — it does not itself grant access. The three
 * concerns stay separate: <b>definition</b> (this row) · <b>assignment</b> (attaching validated values to a
 * resource, on the catalog side) · <b>requirement</b> (a role's {@code requiredTags}, which the policy
 * matches). The {@code key} is unique within its scope via two partial unique indexes keyed on whether
 * {@code team_id} is null — the exact pattern role codes use.
 */
@Entity
@Table(name = "tag_definition")
public class TagDefinition extends AbstractAuditableEntity {

    /** The tag key, e.g. {@code "sensitivity"} or {@code "region"} (kebab-case). */
    @Column(nullable = false)
    private String key;

    /** {@link TagScope#GLOBAL} for system keys, {@link TagScope#TEAM} for team-defined keys. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TagScope scope;

    /** Null for {@code GLOBAL} keys; the owning team for {@code TEAM} keys. */
    @Column(name = "team_id")
    private UUID teamId;

    /** {@link TagValueType#STRING} (free-form, optional pattern) or {@link TagValueType#ENUM} (closed set). */
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false)
    private TagValueType valueType;

    /** {@link TagCardinality#SINGLE} (one scalar) or {@link TagCardinality#MULTI} (a set). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TagCardinality cardinality;

    /** The closed set for an {@code ENUM} key; empty for a {@code STRING} key. Never null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_values", columnDefinition = "jsonb", nullable = false)
    private List<String> allowedValues = List.of();

    /** An optional regex constraining a {@code STRING} value; null means any string is legal. */
    @Column(name = "value_pattern")
    private String valuePattern;

    /** True for seeded global keys; such definitions are immutable via the API. */
    @Column(name = "is_system", nullable = false)
    private boolean system;

    protected TagDefinition() {
        // JPA
    }

    public TagDefinition(
            UUID id,
            String key,
            TagScope scope,
            UUID teamId,
            TagValueType valueType,
            TagCardinality cardinality,
            List<String> allowedValues,
            String valuePattern,
            boolean system) {
        super(id);
        this.key = key;
        this.scope = scope;
        this.teamId = teamId;
        this.valueType = valueType;
        this.cardinality = cardinality;
        this.allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        this.valuePattern = valuePattern;
        this.system = system;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public TagScope getScope() {
        return scope;
    }

    public void setScope(TagScope scope) {
        this.scope = scope;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    public TagValueType getValueType() {
        return valueType;
    }

    public void setValueType(TagValueType valueType) {
        this.valueType = valueType;
    }

    public TagCardinality getCardinality() {
        return cardinality;
    }

    public void setCardinality(TagCardinality cardinality) {
        this.cardinality = cardinality;
    }

    public List<String> getAllowedValues() {
        return allowedValues;
    }

    public void setAllowedValues(List<String> allowedValues) {
        this.allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public String getValuePattern() {
        return valuePattern;
    }

    public void setValuePattern(String valuePattern) {
        this.valuePattern = valuePattern;
    }

    public boolean isSystem() {
        return system;
    }

    public void setSystem(boolean system) {
        this.system = system;
    }
}
