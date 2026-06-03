package dev.dmitriikonovalov.example.usermgmt.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A reusable named permission set — the <b>role</b> half of {@code role ≠ grant}. Two kinds:
 *
 * <ul>
 *   <li><b>system roles</b> — {@code system = true}, {@code teamId = null}: the immutable global ladder
 *       ({@code owner}/{@code administrator}/{@code member}/{@code viewer}), seeded by Liquibase;</li>
 *   <li><b>team-scoped custom roles</b> — {@code system = false}, {@code teamId} set: owner-defined,
 *       live in the DB, scoped to one team (ticket 5).</li>
 * </ul>
 *
 * <p>{@link #permissions} uses the exact {@code {resourceType: [verbs]}} shape the OPA policy reads,
 * so the resolve API (ticket 7) can return a {@code core.RoleDefinition} verbatim. Both JSON-ish maps
 * are stored as JSONB. Named {@code RoleDefinitionEntity} to avoid colliding with the library's
 * {@code dev.dmitriikonovalov.opaabac.core.RoleDefinition} record (the wire/decision shape).
 */
@Entity
@Table(name = "role_definition")
public class RoleDefinitionEntity extends AbstractAuditableEntity {

    /** Stable role code, e.g. {@code "owner"} or a team-scoped {@code "catalog-editor"}. */
    @Column(nullable = false)
    private String code;

    /** True for the seeded global ladder; such roles are immutable via the API. */
    @Column(name = "is_system", nullable = false)
    private boolean system;

    /** Null for system roles; set for team-scoped custom roles (the owning team). */
    @Column(name = "team_id")
    private UUID teamId;

    /** Extensible role attributes (e.g. {@code {"role_level": 30}}); never null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> attributes = Map.of();

    /** {@code resourceType -> [allowed action verbs]}; never null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", columnDefinition = "jsonb", nullable = false)
    private Map<String, List<String>> permissions = Map.of();

    /**
     * Optional tag requirement: {@code tagKey -> [acceptable values]}; never null (empty = no
     * requirement). Carried verbatim into the resolved {@code core.RoleDefinition} (Phase 4.5).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_tags", columnDefinition = "jsonb", nullable = false)
    private Map<String, List<String>> requiredTags = Map.of();

    /** {@code ANY_OF} | {@code ALL_OF}; null when there is no tag requirement. */
    @Column(name = "match_mode")
    private String matchMode;

    protected RoleDefinitionEntity() {
        // JPA
    }

    public RoleDefinitionEntity(
            UUID id,
            String code,
            boolean system,
            UUID teamId,
            Map<String, Object> attributes,
            Map<String, List<String>> permissions) {
        this(id, code, system, teamId, attributes, permissions, Map.of(), null);
    }

    public RoleDefinitionEntity(
            UUID id,
            String code,
            boolean system,
            UUID teamId,
            Map<String, Object> attributes,
            Map<String, List<String>> permissions,
            Map<String, List<String>> requiredTags,
            String matchMode) {
        super(id);
        this.code = code;
        this.system = system;
        this.teamId = teamId;
        this.attributes = attributes == null ? Map.of() : attributes;
        this.permissions = permissions == null ? Map.of() : permissions;
        this.requiredTags = requiredTags == null ? Map.of() : requiredTags;
        this.matchMode = matchMode;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public boolean isSystem() {
        return system;
    }

    public void setSystem(boolean system) {
        this.system = system;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes == null ? Map.of() : attributes;
    }

    public Map<String, List<String>> getPermissions() {
        return permissions;
    }

    public void setPermissions(Map<String, List<String>> permissions) {
        this.permissions = permissions == null ? Map.of() : permissions;
    }

    public Map<String, List<String>> getRequiredTags() {
        return requiredTags;
    }

    public void setRequiredTags(Map<String, List<String>> requiredTags) {
        this.requiredTags = requiredTags == null ? Map.of() : requiredTags;
    }

    public String getMatchMode() {
        return matchMode;
    }

    public void setMatchMode(String matchMode) {
        this.matchMode = matchMode;
    }
}
