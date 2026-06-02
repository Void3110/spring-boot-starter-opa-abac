package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team membership management (ticket 4): add / remove / update-role members, enforcing the
 * <b>no-self-escalation subset rule</b>. {@code @Transactional}; controllers stay thin and delegate.
 *
 * <p>Authorization of <em>who</em> may manage is the {@code @OpaPreAuthorize(action="team:manage")}
 * decision on the controller (against the calling subject's resolved team role). This service enforces
 * the orthogonal invariant: the role being assigned may not exceed the <em>actor's own</em> effective
 * permissions on the team (the subset rule), so even an authorized manager cannot escalate.
 */
@Service
public class MembershipService {

    private final TeamMembershipRepository memberships;
    private final TeamRepository teams;
    private final UserRepository users;
    private final RoleDefinitionRepository roles;
    private final EffectiveRoleService effectiveRoles;

    public MembershipService(
            TeamMembershipRepository memberships,
            TeamRepository teams,
            UserRepository users,
            RoleDefinitionRepository roles,
            EffectiveRoleService effectiveRoles) {
        this.memberships = memberships;
        this.teams = teams;
        this.users = users;
        this.roles = roles;
        this.effectiveRoles = effectiveRoles;
    }

    @Transactional(readOnly = true)
    public List<MembershipView> list(UUID teamId) {
        requireTeam(teamId);
        return memberships.findByTeamId(teamId).stream().map(this::toView).toList();
    }

    /**
     * Add a member with a role (by role code — a system role, or a team-scoped custom role of this
     * team). Enforces the subset rule against the actor's effective permissions.
     */
    @Transactional
    public MembershipView addMember(UUID actorUserId, UUID teamId, UUID userId, String roleCode) {
        requireTeam(teamId);
        if (!users.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        if (memberships.existsByTeamIdAndUserId(teamId, userId)) {
            throw new MembershipConflictException(
                    "User " + userId + " is already a member of team " + teamId);
        }
        RoleDefinitionEntity role = resolveAssignableRole(teamId, roleCode);
        requireSubsetOfActor(actorUserId, teamId, role);
        var saved = memberships.save(new TeamMembership(UUID.randomUUID(), teamId, userId, role.getId()));
        return new MembershipView(saved, role.getCode());
    }

    /** Change a member's role, subject to the subset rule. */
    @Transactional
    public MembershipView changeRole(UUID actorUserId, UUID teamId, UUID userId, String roleCode) {
        requireTeam(teamId);
        TeamMembership membership = memberships.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new MembershipNotFoundException(teamId, userId));
        RoleDefinitionEntity role = resolveAssignableRole(teamId, roleCode);
        requireSubsetOfActor(actorUserId, teamId, role);
        membership.setRoleDefinitionId(role.getId());
        return new MembershipView(memberships.save(membership), role.getCode());
    }

    /** Remove a member — revokes all access derived through the team (resolve re-derives). */
    @Transactional
    public void removeMember(UUID teamId, UUID userId) {
        requireTeam(teamId);
        TeamMembership membership = memberships.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new MembershipNotFoundException(teamId, userId));
        memberships.delete(membership);
    }

    private MembershipView toView(TeamMembership membership) {
        return new MembershipView(membership, effectiveRoles.roleOf(membership).getCode());
    }

    private void requireTeam(UUID teamId) {
        if (!teams.existsById(teamId)) {
            throw new IllegalArgumentException("Team not found: " + teamId);
        }
    }

    /** A system role (by code) or this team's custom role (by code). */
    private RoleDefinitionEntity resolveAssignableRole(UUID teamId, String roleCode) {
        return roles.findBySystemTrueAndCode(roleCode)
                .or(() -> roles.findByTeamIdAndCode(teamId, roleCode))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Role '" + roleCode + "' is not a system role or a custom role of team " + teamId));
    }

    /**
     * The subset rule: the candidate role's permissions must not exceed the actor's own effective
     * permissions on the team. An actor with no membership has no permissions → cannot assign anything.
     */
    private void requireSubsetOfActor(UUID actorUserId, UUID teamId, RoleDefinitionEntity candidate) {
        var actorPerms = effectiveRoles.membership(teamId, actorUserId)
                .map(effectiveRoles::roleOf)
                .map(RoleDefinitionEntity::getPermissions)
                .orElseThrow(() -> new SubsetRuleViolationException(
                        "Actor has no membership on team " + teamId + " and cannot assign roles"));
        if (!PermissionSubset.isSubset(candidate.getPermissions(), actorPerms)) {
            throw new SubsetRuleViolationException(
                    "Role '" + candidate.getCode() + "' exceeds the actor's own permissions (subset rule)");
        }
    }
}
