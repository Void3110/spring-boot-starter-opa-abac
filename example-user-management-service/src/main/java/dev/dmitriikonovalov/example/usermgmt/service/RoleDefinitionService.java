package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** System roles plus this team's custom roles — the team's full role list, paged (5.95). */
    @Transactional(readOnly = true)
    public Page<RoleDefinitionEntity> list(UUID teamId, Pageable pageable) {
        requireTeam(teamId);
        return roles.findBySystemTrueOrTeamId(teamId, pageable);
    }

    /** Create a team-scoped custom role ({@code system=false}, {@code teamId} set), subset-guarded. */
    @Transactional
    public RoleDefinitionEntity create(
            UUID actorUserId,
            UUID teamId,
            String code,
            Map<String, Object> attributes,
            Map<String, List<String>> permissions,
            Map<String, List<String>> requiredTags,
            String matchMode) {
        lockTeam(teamId);
        if (roles.findBySystemTrueAndCode(code).isPresent()) {
            throw new RoleConflictException("'" + code + "' is a reserved system role code");
        }
        if (roles.findByTeamIdAndCode(teamId, code).isPresent()) {
            throw new RoleConflictException(
                    "A custom role '" + code + "' already exists on team " + teamId);
        }
        subsetGuard.requireWithinActorPermissions(actorUserId, teamId, permissions);
        return roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(), code, false, teamId, attributes, permissions,
                requiredTags, normalizeMatchMode(requiredTags, matchMode)));
    }

    /** Update a team-scoped custom role's attributes/permissions, subset-guarded. System roles are immutable. */
    @Transactional
    public RoleDefinitionEntity update(
            UUID actorUserId,
            UUID teamId,
            String code,
            Map<String, Object> attributes,
            Map<String, List<String>> permissions,
            Map<String, List<String>> requiredTags,
            String matchMode) {
        lockTeam(teamId);
        RoleDefinitionEntity role = requireCustomRole(teamId, code);
        subsetGuard.requireWithinActorPermissions(actorUserId, teamId, permissions);
        role.setAttributes(attributes);
        role.setPermissions(permissions);
        role.setRequiredTags(requiredTags);
        role.setMatchMode(normalizeMatchMode(requiredTags, matchMode));
        return roles.save(role);
    }

    /**
     * {@code match_mode} is meaningful only with a tag requirement: default it to {@code ANY_OF} when
     * required tags are present and none was given; clear it when there is no requirement. This mirrors
     * the {@code core.RoleDefinition} normalization so the stored row and the resolved role agree.
     */
    private static String normalizeMatchMode(
            Map<String, List<String>> requiredTags, String matchMode) {
        boolean hasRequirement = requiredTags != null && !requiredTags.isEmpty();
        if (!hasRequirement) {
            return null;
        }
        return matchMode == null || matchMode.isBlank() ? "ANY_OF" : matchMode;
    }

    /** Delete a team-scoped custom role. System roles are immutable. */
    @Transactional
    public void delete(UUID teamId, String code) {
        lockTeam(teamId);
        RoleDefinitionEntity role = requireCustomRole(teamId, code);
        roles.delete(role);
        // Flush HERE so deleting an in-use role (the team_membership FK) raises a translated
        // DataIntegrityViolationException inside the method — the starter advice maps it to 409.
        // Deferred to commit, the violation can surface as an untranslated wrapper → 500.
        roles.flush();
    }

    private void requireTeam(UUID teamId) {
        if (!teams.existsById(teamId)) {
            throw new IllegalArgumentException("Team not found: " + teamId);
        }
    }

    /**
     * Lock the team row {@code FOR UPDATE} for the rest of the transaction — role definitions feed the
     * subset/ceiling decisions, so their writes serialize with every other team-scoped grant mutation
     * (see {@code MembershipService}'s class doc). Doubles as the existence check.
     */
    private void lockTeam(UUID teamId) {
        teams.findByIdForUpdate(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
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
