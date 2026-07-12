package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.AddMemberRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.ChangeRoleRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Membership;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Ticket-4 team-management ITs (M1–M7 + the policy via the in-process client): owner/admin manage,
 * member/viewer are denied, the subset rule blocks escalation, and — crucially — the decision tracks
 * the <b>actor</b> (the calling subject, named in the request's subject header), not the service.
 */
class MembershipManagementIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;
    @Autowired private RoleDefinitionRepository roles;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team team() {
        return teams.save(new Team(UUID.randomUUID(), "Acme", "catalog", UUID.randomUUID()));
    }

    private void grant(Team team, User user, UUID roleId) {
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), roleId));
    }

    @Test
    void ownerAddsAndRemovesMember() {
        Team team = team();
        User owner = user("owner");
        User newbie = user("newbie");
        grant(team, owner, SystemRoles.OWNER_ID);

        var add = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        owner.getSubject(),
                        new AddMemberRequest().userId(newbie.getId()).roleCode(SystemRoles.MEMBER)),
                Membership.class,
                team.getId());
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add.getBody()).isNotNull();
        assertThat(add.getBody().getRoleCode()).isEqualTo(SystemRoles.MEMBER);
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), newbie.getId())).isPresent();

        var remove = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.DELETE,
                AbacTestConfig.as(owner.getSubject()),
                Void.class,
                team.getId(),
                newbie.getId());
        assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Removal revokes (membership is the source of truth).
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), newbie.getId())).isEmpty();
    }

    @Test
    void ownerChangesMemberRole() {
        Team team = team();
        User owner = user("owner");
        User member = user("member");
        grant(team, owner, SystemRoles.OWNER_ID);
        grant(team, member, SystemRoles.READER_ID);

        var change = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.PUT,
                AbacTestConfig.as(owner.getSubject(), new ChangeRoleRequest().roleCode(SystemRoles.MEMBER)),
                Membership.class,
                team.getId(),
                member.getId());
        assertThat(change.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(change.getBody()).isNotNull();
        assertThat(change.getBody().getRoleCode()).isEqualTo(SystemRoles.MEMBER);
    }

    @Test
    void administratorCanManage() {
        Team team = team();
        User admin = user("admin");
        User newbie = user("newbie");
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        var add = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        admin.getSubject(),
                        new AddMemberRequest().userId(newbie.getId()).roleCode(SystemRoles.READER)),
                Membership.class,
                team.getId());
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void memberCannotManage() {
        Team team = team();
        User member = user("member");
        User newbie = user("newbie");
        grant(team, member, SystemRoles.MEMBER_ID);

        var add = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        member.getSubject(),
                        new AddMemberRequest().userId(newbie.getId()).roleCode(SystemRoles.READER)),
                String.class,
                team.getId());
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test // Phase 6.7: list-members moved to READ — a reader can now SEE the roster (the loosening) but
    // still cannot MUTATE membership (the loosening is exactly listing, nothing wider).
    void readerListsButCannotManage() {
        Team team = team();
        User viewer = user("viewer");
        User newbie = user("newbie");
        grant(team, viewer, SystemRoles.READER_ID);

        var list = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.GET,
                AbacTestConfig.as(viewer.getSubject()),
                String.class,
                team.getId());
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

        var add = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        viewer.getSubject(),
                        new AddMemberRequest().userId(newbie.getId()).roleCode(SystemRoles.READER)),
                String.class,
                team.getId());
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test // pinned semantic #5: a candidate snapshot with NO numeric role_level rejects — fail-closed
    void roleWithoutLevelIsNotAssignable() {
        Team team = team();
        User admin = user("admin");
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);
        User target = user("target");
        grant(team, target, SystemRoles.READER_ID);

        // A stale custom row (seeded directly, bypassing the authoring contract) carrying no
        // role_level — the level gate must reject it, never treat the missing level as 0-and-pass.
        RoleDefinitionEntity stale = roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(),
                "stale-levelless",
                false,
                team.getId(),
                Map.of(),
                Map.of("catalog", List.of("READ"))));

        var change = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.PUT,
                AbacTestConfig.as(admin.getSubject(), new ChangeRoleRequest().roleCode(stale.getCode())),
                String.class,
                team.getId(),
                target.getId());
        // Authorized to manage (admin), but the level gate rejects (mechanism: missing role_level).
        assertThat(change.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(change.getBody()).contains("ROLE_SUBSET_VIOLATION");
        // The target's membership row is unchanged.
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), target.getId()))
                .get()
                .extracting(TeamMembership::getRoleDefinitionId)
                .isEqualTo(SystemRoles.READER_ID);
    }

    @Test
    void administratorCannotSelfPromoteToOwner() {
        // Retro-audit 2026-06-12 (Critical): owner and administrator carry IDENTICAL resource
        // permissions ({"*": [read, write]}), so the resource-permission subset check alone passes —
        // only the owner-assignment guard / capability-ladder rule stops the escalation to
        // define-roles + transfer-ownership.
        Team team = team();
        User admin = user("admin");
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        var promote = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.PUT,
                AbacTestConfig.as(admin.getSubject(), new ChangeRoleRequest().roleCode(SystemRoles.OWNER)),
                String.class,
                team.getId(),
                admin.getId());
        assertThat(promote.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The membership row is unchanged — still bound to the administrator role.
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), admin.getId()))
                .get()
                .extracting(TeamMembership::getRoleDefinitionId)
                .isEqualTo(SystemRoles.ADMINISTRATOR_ID);
    }

    @Test
    void ownerCannotBeDemotedOrRemovedViaMembershipEndpoints() {
        // The mirror of the owner-assignment guard: stripping or demoting the sole owner would leave
        // the team ownerless — ownership moves only through the transfer-ownership flow.
        Team team = team();
        User owner = user("owner");
        User admin = user("admin");
        grant(team, owner, SystemRoles.OWNER_ID);
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        var demote = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.PUT,
                AbacTestConfig.as(admin.getSubject(), new ChangeRoleRequest().roleCode(SystemRoles.MEMBER)),
                String.class,
                team.getId(),
                owner.getId());
        assertThat(demote.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var strip = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.DELETE,
                AbacTestConfig.as(admin.getSubject()),
                String.class,
                team.getId(),
                owner.getId());
        assertThat(strip.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The owner's membership row is intact, still bound to the owner role.
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), owner.getId()))
                .get()
                .extracting(TeamMembership::getRoleDefinitionId)
                .isEqualTo(SystemRoles.OWNER_ID);
    }

    @Test
    void ownerRoleIsNotAssignableEvenByAnOwner() {
        // Adding a second owner would break the exactly-one-owner invariant; ownership moves only
        // through the transfer-ownership flow (which downgrades the previous owner atomically).
        Team team = team();
        User owner = user("owner");
        User other = user("other");
        grant(team, owner, SystemRoles.OWNER_ID);

        var mint = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        owner.getSubject(),
                        new AddMemberRequest().userId(other.getId()).roleCode(SystemRoles.OWNER)),
                String.class,
                team.getId());
        assertThat(mint.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), other.getId())).isEmpty();
    }

    @Test
    void decisionAuthorizesTheActorNotTheService() {
        // Two teams. The caller owns team A but is a mere viewer on team B. Managing team B must be
        // denied even though the same service identity could, in principle, write anything — the
        // decision tracks the calling subject's role on the team being managed.
        Team teamA = team();
        Team teamB = team();
        User caller = user("caller");
        User someoneElse = user("else");
        grant(teamA, caller, SystemRoles.OWNER_ID);
        grant(teamB, caller, SystemRoles.READER_ID);

        var onB = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        caller.getSubject(),
                        new AddMemberRequest().userId(someoneElse.getId()).roleCode(SystemRoles.MEMBER)),
                String.class,
                teamB.getId());
        assertThat(onB.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // ...but managing team A (where the caller is owner) succeeds — same caller, different team.
        var onA = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        caller.getSubject(),
                        new AddMemberRequest().userId(someoneElse.getId()).roleCode(SystemRoles.MEMBER)),
                Membership.class,
                teamA.getId());
        assertThat(onA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void unauthenticatedIsDenied() {
        Team team = team();
        // No subject header → no subject → the secured chain rejects before authorization.
        var list = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.GET,
                AbacTestConfig.as(null),
                String.class,
                team.getId());
        assertThat(list.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
