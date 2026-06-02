package dev.dmitriikonovalov.example.usermgmt.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * The <b>grant</b> half of {@code role ≠ grant}: a binding of {@code principal × role × team scope}.
 * The membership row <em>carries the role</em> — a user's role on a team is whichever
 * {@link RoleDefinitionEntity} this row points at. "Team-scoped" means the binding's scope is the
 * team, not a role-per-team.
 *
 * <p>Unique on {@code (team_id, user_id)}: one role per user per team (kept simple for the demo;
 * multi-role is additive later). Membership is the <b>single source of truth</b> — removing this row
 * revokes all access derived through it, and the resolve API always re-derives (no stale grants).
 */
@Entity
@Table(
        name = "team_membership",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_team_membership_team_user",
                columnNames = {"team_id", "user_id"}))
public class TeamMembership extends AbstractAuditableEntity {

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_definition_id", nullable = false)
    private UUID roleDefinitionId;

    protected TeamMembership() {
        // JPA
    }

    public TeamMembership(UUID id, UUID teamId, UUID userId, UUID roleDefinitionId) {
        super(id);
        this.teamId = teamId;
        this.userId = userId;
        this.roleDefinitionId = roleDefinitionId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getRoleDefinitionId() {
        return roleDefinitionId;
    }

    public void setRoleDefinitionId(UUID roleDefinitionId) {
        this.roleDefinitionId = roleDefinitionId;
    }
}
