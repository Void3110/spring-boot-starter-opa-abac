package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final SubsetGuard subsetGuard;

    public MembershipService(
            TeamMembershipRepository memberships,
            TeamRepository teams,
            UserRepository users,
            RoleDefinitionRepository roles,
            EffectiveRoleService effectiveRoles,
            SubsetGuard subsetGuard) {
        this.memberships = memberships;
        this.teams = teams;
        this.users = users;
        this.roles = roles;
        this.effectiveRoles = effectiveRoles;
        this.subsetGuard = subsetGuard;
    }

    /** The team's members, paged (5.95); each row carries its bound role's code. */
    @Transactional(readOnly = true)
    public Page<MembershipView> list(UUID teamId, Pageable pageable) {
        requireTeam(teamId);
        return memberships.findByTeamId(teamId, pageable).map(this::toView);
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
        subsetGuard.requireAssignableByActor(actorUserId, teamId, role);
        var saved = memberships.save(new TeamMembership(UUID.randomUUID(), teamId, userId, role.getId()));
        return new MembershipView(saved, role.getCode());
    }

    /** Change a member's role, subject to the subset rule. */
    @Transactional
    public MembershipView changeRole(UUID actorUserId, UUID teamId, UUID userId, String roleCode) {
        requireTeam(teamId);
        TeamMembership membership = memberships.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new MembershipNotFoundException(teamId, userId));
        requireTargetIsNotTheOwner(membership, "demoted");
        RoleDefinitionEntity role = resolveAssignableRole(teamId, roleCode);
        subsetGuard.requireAssignableByActor(actorUserId, teamId, role);
        membership.setRoleDefinitionId(role.getId());
        return new MembershipView(memberships.save(membership), role.getCode());
    }

    /** Remove a member — revokes all access derived through the team (resolve re-derives). */
    @Transactional
    public void removeMember(UUID teamId, UUID userId) {
        requireTeam(teamId);
        TeamMembership membership = memberships.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new MembershipNotFoundException(teamId, userId));
        requireTargetIsNotTheOwner(membership, "removed");
        memberships.delete(membership);
    }

    /**
     * The mirror of the owner-assignment guard: the sole owner can be neither demoted nor removed
     * through membership endpoints (the team would be left ownerless) — ownership moves only via the
     * transfer-ownership flow.
     */
    private void requireTargetIsNotTheOwner(TeamMembership membership, String operation) {
        if (SystemRoles.OWNER.equals(effectiveRoles.roleOf(membership).getCode())) {
            throw new IllegalArgumentException(
                    "The team owner cannot be " + operation + " through membership endpoints;"
                            + " use the transfer-ownership operation");
        }
    }

    private MembershipView toView(TeamMembership membership) {
        return new MembershipView(membership, effectiveRoles.roleOf(membership).getCode());
    }

    private void requireTeam(UUID teamId) {
        if (!teams.existsById(teamId)) {
            throw new IllegalArgumentException("Team not found: " + teamId);
        }
    }

    /**
     * A system role (by code) or this team's custom role (by code). The {@code owner} code is never
     * assignable here: the team has exactly one owner (set at creation), and ownership moves only
     * through the transfer-ownership flow, which atomically downgrades the previous owner.
     */
    private RoleDefinitionEntity resolveAssignableRole(UUID teamId, String roleCode) {
        if (SystemRoles.OWNER.equals(roleCode)) {
            throw new IllegalArgumentException(
                    "The 'owner' role cannot be assigned through membership endpoints;"
                            + " use the transfer-ownership operation");
        }
        return roles.findBySystemTrueAndCode(roleCode)
                .or(() -> roles.findByTeamIdAndCode(teamId, roleCode))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Role '" + roleCode + "' is not a system role or a custom role of team " + teamId));
    }
}
