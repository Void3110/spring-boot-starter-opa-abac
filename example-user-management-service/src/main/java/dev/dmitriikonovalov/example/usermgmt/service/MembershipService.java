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
 * <b>hybrid assignment gates</b> (Phase 6.5, ADR 0007). {@code @Transactional}; controllers stay thin
 * and delegate.
 *
 * <p>Authorization of <em>who</em> may manage is the {@code @OpaPreAuthorize} decision on the
 * controller — since Phase 6.7 (ADR 0015) the fine membership verbs {@code team:add-member} /
 * {@code team:change-role} / {@code team:remove-member} (the {@code CONTROL} category), against the
 * calling subject's resolved team role. That is the <em>verb-category</em> axis; this service enforces
 * the orthogonal <em>escalation</em> axis (the two-axis split) via two gates over <b>lock-read
 * snapshots</b>:
 * <ol>
 *   <li><b>cross-tier (everyone)</b>: the actor's {@code role_level} must be strictly above the
 *       candidate role's — a missing/non-numeric level on either side <b>rejects</b> (never
 *       0-and-pass; pinned semantic #5);</li>
 *   <li><b>at senior (25) additionally</b>: the candidate must sit at or below the member tier (≤ 20)
 *       <b>and</b> OPA's {@code data.role.assignable} subset-on-effective verdict must positively
 *       answer {@code true} ({@link RoleAssignableClient} — any OPA failure rejects).</li>
 * </ol>
 * Every rejection is the one {@code 422 ROLE_SUBSET_VIOLATION} contract.
 *
 * <p><b>Decide under protection</b> (CONCURRENCY-AND-LOCKING Rules 1–2): every mutating flow locks the
 * <em>team row</em> {@code FOR UPDATE} first, and so does every other team-scoped grant mutation
 * ({@code TeamService.transferOwnership}, the custom-role definition writes). Both gates AND the
 * {@code assignable} snapshots read state <b>after</b> the lock in the same transaction — without it,
 * a parallel demotion of the actor could land between the check and the grant, letting an ex-admin
 * confer roles they no longer hold (the Critical-1 race, re-proven by {@code MembershipConcurrencyIT}).
 */
@Service
public class MembershipService {

    /** The senior tier — the only tier whose assignments also need the OPA subset verdict. */
    private static final int SENIOR_LEVEL = 25;

    /** The member tier — the highest tier a senior may assign. */
    private static final int MEMBER_LEVEL = 20;

    private final TeamMembershipRepository memberships;
    private final TeamRepository teams;
    private final UserRepository users;
    private final RoleDefinitionRepository roles;
    private final EffectiveRoleService effectiveRoles;
    private final RoleAssignableClient roleAssignableClient;

    public MembershipService(
            TeamMembershipRepository memberships,
            TeamRepository teams,
            UserRepository users,
            RoleDefinitionRepository roles,
            EffectiveRoleService effectiveRoles,
            RoleAssignableClient roleAssignableClient) {
        this.memberships = memberships;
        this.teams = teams;
        this.users = users;
        this.roles = roles;
        this.effectiveRoles = effectiveRoles;
        this.roleAssignableClient = roleAssignableClient;
    }

    /** The team's members, paged (5.95); each row carries its bound role's code. */
    @Transactional(readOnly = true)
    public Page<MembershipView> list(UUID teamId, Pageable pageable) {
        requireTeam(teamId);
        return memberships.findByTeamId(teamId, pageable).map(this::toView);
    }

    /**
     * Add a member with a role (by role code — a system role, or a team-scoped custom role of this
     * team). Enforces the hybrid assignment gates against lock-read snapshots.
     */
    @Transactional
    public MembershipView addMember(UUID actorUserId, UUID teamId, UUID userId, String roleCode) {
        lockTeam(teamId);
        if (!users.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        if (memberships.existsByTeamIdAndUserId(teamId, userId)) {
            throw new MembershipConflictException(
                    "User " + userId + " is already a member of team " + teamId);
        }
        RoleDefinitionEntity role = resolveAssignableRole(teamId, roleCode);
        requireAssignableByActor(requireActorRole(teamId, actorUserId), role);
        var saved = memberships.save(new TeamMembership(UUID.randomUUID(), teamId, userId, role.getId()));
        return new MembershipView(saved, role.getCode());
    }

    /** Change a member's role, subject to the hybrid assignment gates + the target-tier gate. */
    @Transactional
    public MembershipView changeRole(UUID actorUserId, UUID teamId, UUID userId, String roleCode) {
        lockTeam(teamId);
        TeamMembership membership = memberships.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new MembershipNotFoundException(teamId, userId));
        requireTargetIsNotTheOwner(membership, "demoted");
        RoleDefinitionEntity actorRole = requireActorRole(teamId, actorUserId);
        requireTargetDoesNotOutrankActor(actorRole, membership, "demoted");
        RoleDefinitionEntity role = resolveAssignableRole(teamId, roleCode);
        requireAssignableByActor(actorRole, role);
        membership.setRoleDefinitionId(role.getId());
        return new MembershipView(memberships.save(membership), role.getCode());
    }

    /**
     * The hybrid assignment gates (Phase 6.5; called <b>after</b> {@code lockTeam} in the same
     * transaction — both snapshots are lock-read state):
     * <ol>
     *   <li><b>cross-tier</b>: {@code actorLevel > candidateLevel} (strict — an admin cannot mint a
     *       peer admin); levels come from {@code attributes.role_level} and an unreadable level on
     *       either side rejects;</li>
     *   <li><b>at senior (25)</b>: the candidate must be at or below member tier (≤ 20) AND OPA's
     *       {@code data.role.assignable} verdict over the two raw snapshots must be {@code true} —
     *       {@code false} (including any OPA failure) rejects.</li>
     * </ol>
     * The level gate runs first, so {@code assignable} is never consulted when the tier already
     * rejects (and never for non-senior actors).
     */
    private void requireAssignableByActor(RoleDefinitionEntity actorRole, RoleDefinitionEntity candidate) {
        int actorLevel = levelOf(actorRole, "actor");
        int candidateLevel = levelOf(candidate, "candidate");
        if (actorLevel <= candidateLevel) {
            throw new SubsetRuleViolationException(
                    "The role's tier is not below the actor's own (cross-tier rule)");
        }
        if (actorLevel == SENIOR_LEVEL) {
            if (candidateLevel > MEMBER_LEVEL) {
                throw new SubsetRuleViolationException(
                        "A senior may only assign roles at or below the member tier");
            }
            if (!roleAssignableClient.assignable(actorRole, candidate)) {
                throw new SubsetRuleViolationException(
                        "The role is not assignable by the actor (subset-on-effective rule)");
            }
        }
    }

    /**
     * A snapshot's {@code attributes.role_level}, REQUIRED numeric: a missing or non-numeric level
     * rejects the assignment (pinned semantic #5) — never 0-and-pass, never a wildcard.
     */
    private static int levelOf(RoleDefinitionEntity role, String side) {
        Object level = role.getAttributes().get("role_level");
        if (level instanceof Number n) {
            return n.intValue();
        }
        throw new SubsetRuleViolationException(
                "The " + side + " role '" + role.getCode()
                        + "' carries no numeric role_level — assignment rejected (fail-closed)");
    }

    /**
     * Remove a member — revokes all access derived through the team (resolve re-derives). Subject to
     * the target-tier gate: removal is management OF the target, so the target must not outrank the
     * actor (a senior cannot remove an administrator).
     */
    @Transactional
    public void removeMember(UUID actorUserId, UUID teamId, UUID userId) {
        lockTeam(teamId);
        TeamMembership membership = memberships.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new MembershipNotFoundException(teamId, userId));
        requireTargetIsNotTheOwner(membership, "removed");
        requireTargetDoesNotOutrankActor(requireActorRole(teamId, actorUserId), membership, "removed");
        memberships.delete(membership);
    }

    /** The actor's own role on this team (lock-read) — an actor with no membership cannot manage. */
    private RoleDefinitionEntity requireActorRole(UUID teamId, UUID actorUserId) {
        return effectiveRoles.membership(teamId, actorUserId)
                .map(effectiveRoles::roleOf)
                .orElseThrow(() -> new SubsetRuleViolationException(
                        "Actor has no membership on team " + teamId + " and cannot manage members"));
    }

    /**
     * The demote/remove mirror of the cross-tier gate (review fix, 2026-06-12): a manager may not act
     * <b>on</b> a member whose CURRENT tier is above their own. Phase 6.5 made this reachable: the
     * senior tier is the first manage-holder that sits below another non-owner tier, and without this
     * gate a senior could demote or remove an administrator. Peers stay manageable (an admin can
     * remove a peer admin — the pre-6.5 behavior, unchanged). The asymmetry with {@link #levelOf} is
     * deliberate: an unreadable TARGET level never outranks — removal/demotion only <em>narrows</em>
     * the target's access, and a member holding a corrupted role must stay removable. The ACTOR side
     * stays strict (an actor with an unreadable level cannot manage anyone).
     */
    private void requireTargetDoesNotOutrankActor(
            RoleDefinitionEntity actorRole, TeamMembership target, String operation) {
        int actorLevel = levelOf(actorRole, "actor");
        Object targetLevel = effectiveRoles.roleOf(target).getAttributes().get("role_level");
        if (targetLevel instanceof Number n && n.intValue() > actorLevel) {
            throw new SubsetRuleViolationException(
                    "The member's current tier is above the actor's and cannot be " + operation
                            + " (target-tier rule)");
        }
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
     * Lock the team row {@code FOR UPDATE} for the rest of the transaction — the serialization point
     * for ALL team-scoped grant mutations (see the class doc). Doubles as the existence check.
     */
    private void lockTeam(UUID teamId) {
        teams.findByIdForUpdate(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
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
