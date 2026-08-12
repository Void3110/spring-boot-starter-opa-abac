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

    /**
     * True when <em>values</em> under this key may only be written by the operator — never through any
     * public API path. Distinct from {@link #system}, which protects the <em>definition</em> from
     * mutation: a key can be immutable-by-definition yet freely assignable (both seeded GLOBAL keys are),
     * and this flag is what makes {@code env} the opposite — assignable in principle, but only by the
     * operator's in-network path. Defaults to {@code false}, so every pre-existing key is untouched.
     */
    @Column(name = "operator_managed", nullable = false)
    private boolean operatorManaged;

    protected TagDefinition() {
        // JPA
    }

    /** Convenience overload for the ordinary, non-operator-managed key ({@code operatorManaged = false}). */
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
        this(id, key, scope, teamId, valueType, cardinality, allowedValues, valuePattern, system, false);
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
            boolean system,
            boolean operatorManaged) {
        super(id);
        this.key = key;
        this.scope = scope;
        this.teamId = teamId;
        this.valueType = valueType;
        this.cardinality = cardinality;
        this.allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        this.valuePattern = valuePattern;
        this.system = system;
        this.operatorManaged = operatorManaged;
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

    /**
     * Deliberately <b>read-only</b> — no setter, unlike every sibling field. The flag exists to say "these
     * values are not writable through the API"; a public mutator on it would be the first affordance a
     * future caller reached for. A key becomes operator-managed by being seeded that way, never by being
     * set that way at runtime.
     */
    public boolean isOperatorManaged() {
        return operatorManaged;
    }
}
