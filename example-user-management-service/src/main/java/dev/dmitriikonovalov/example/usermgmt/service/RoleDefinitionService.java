package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team-scoped custom role-definition management (ticket 5). An owner defines roles scoped to one team,
 * within the subset rule. {@code @Transactional}; controllers stay thin.
 *
 * <ul>
 *   <li><b>System roles are immutable</b> — update/delete of a {@code system=true} role is rejected
 *       (the global ladder is stable contract).</li>
 *   <li><b>Subset-of-own guard</b> — a custom role's permissions may not exceed the creator's own
 *       effective permissions on the team (the same {@link PermissionSubset} the membership API uses).</li>
 * </ul>
 *
 * <p>Authorization of <em>who</em> may define roles is the {@code @OpaPreAuthorize(action=
 * "team:define-roles")} decision on the controller — owner only (only the owner's management ladder
 * carries the {@code define-roles} verb).
 */
@Service
public class RoleDefinitionService {

    private final RoleDefinitionRepository roles;
    private final TeamRepository teams;
    private final SubsetGuard subsetGuard;

    public RoleDefinitionService(
            RoleDefinitionRepository roles, TeamRepository teams, SubsetGuard subsetGuard) {
        this.roles = roles;
        this.teams = teams;
        this.subsetGuard = subsetGuard;
    }

    /** System roles plus this team's custom roles — the team's full role list. */
    @Transactional(readOnly = true)
    public List<RoleDefinitionEntity> list(UUID teamId) {
        requireTeam(teamId);
        return roles.findBySystemTrueOrTeamId(teamId);
    }

    /** Create a team-scoped custom role ({@code system=false}, {@code teamId} set), subset-guarded. */
    @Transactional
    public RoleDefinitionEntity create(
            UUID actorUserId,
            UUID teamId,
            String code,
            Map<String, Object> attributes,
            Map<String, List<String>> permissions) {
        requireTeam(teamId);
        if (roles.findBySystemTrueAndCode(code).isPresent()) {
            throw new RoleConflictException("'" + code + "' is a reserved system role code");
        }
        if (roles.findByTeamIdAndCode(teamId, code).isPresent()) {
            throw new RoleConflictException(
                    "A custom role '" + code + "' already exists on team " + teamId);
        }
        subsetGuard.requireWithinActorPermissions(actorUserId, teamId, permissions);
        return roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(), code, false, teamId, attributes, permissions));
    }

    /** Update a team-scoped custom role's attributes/permissions, subset-guarded. System roles are immutable. */
    @Transactional
    public RoleDefinitionEntity update(
            UUID actorUserId,
            UUID teamId,
            String code,
            Map<String, Object> attributes,
            Map<String, List<String>> permissions) {
        requireTeam(teamId);
        RoleDefinitionEntity role = requireCustomRole(teamId, code);
        subsetGuard.requireWithinActorPermissions(actorUserId, teamId, permissions);
        role.setAttributes(attributes);
        role.setPermissions(permissions);
        return roles.save(role);
    }

    /** Delete a team-scoped custom role. System roles are immutable. */
    @Transactional
    public void delete(UUID teamId, String code) {
        requireTeam(teamId);
        RoleDefinitionEntity role = requireCustomRole(teamId, code);
        roles.delete(role);
    }

    private void requireTeam(UUID teamId) {
        if (!teams.existsById(teamId)) {
            throw new IllegalArgumentException("Team not found: " + teamId);
        }
    }

    /**
     * A custom role of this team by code. A system role (same code) is rejected as immutable; a missing
     * role is a 404.
     */
    private RoleDefinitionEntity requireCustomRole(UUID teamId, String code) {
        if (roles.findBySystemTrueAndCode(code).isPresent()) {
            throw new SystemRoleImmutableException(code);
        }
        return roles.findByTeamIdAndCode(teamId, code)
                .orElseThrow(() -> new RoleNotFoundException(teamId, code));
    }
}
