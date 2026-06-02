package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional heart of the team abstraction. This is the first class in the {@code service/}
 * package — the deliberate divergence from the catalog app's flat structure (see
 * {@code 00-DESIGN.md} "Internal structure"): the user-service's invariants are inherently
 * cross-entity, so they live in {@code @Transactional} service methods, never in controllers.
 *
 * <p>Ticket 3 implements <b>owner-on-create</b>; transfer-ownership joins this class in ticket 6.
 */
@Service
public class TeamService {

    private final TeamRepository teams;
    private final TeamMembershipRepository memberships;
    private final RoleDefinitionRepository roles;
    private final UserRepository users;

    public TeamService(
            TeamRepository teams,
            TeamMembershipRepository memberships,
            RoleDefinitionRepository roles,
            UserRepository users) {
        this.teams = teams;
        this.memberships = memberships;
        this.roles = roles;
        this.users = users;
    }

    /**
     * Owner-on-create (the bootstrap rule). In <b>one transaction</b>: create the {@link Team} with its
     * team-target, then write the {@code owner} {@link TeamMembership} for the creator. There is never
     * a grant-less resource — any failure rolls the whole thing back (no orphan team, no grant-less
     * target). The {@code owner} system role is resolved from the seeded role (stable id/code).
     *
     * @throws TeamTargetExistsException if a team already governs this {@code (targetType, targetId)}
     *     (one team per team-target — resource→team indirection)
     * @throws IllegalArgumentException  if the creator user does not exist
     */
    @Transactional
    public Team createWithOwner(UUID creatorUserId, String name, String targetType, UUID targetId) {
        if (!users.existsById(creatorUserId)) {
            throw new IllegalArgumentException("Creator user not found: " + creatorUserId);
        }
        if (teams.existsByTargetTypeAndTargetId(targetType, targetId)) {
            throw new TeamTargetExistsException(targetType, targetId);
        }

        var ownerRole = roles.findBySystemTrueAndCode(SystemRoles.OWNER)
                .orElseThrow(() -> new IllegalStateException(
                        "System role '" + SystemRoles.OWNER + "' is not seeded"));

        var team = teams.save(new Team(UUID.randomUUID(), name, targetType, targetId));
        memberships.save(new TeamMembership(
                UUID.randomUUID(), team.getId(), creatorUserId, ownerRole.getId()));
        return team;
    }
}
