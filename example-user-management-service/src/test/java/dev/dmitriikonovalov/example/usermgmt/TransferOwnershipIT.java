package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TransferOwnershipRequest;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Ticket-6 transfer-ownership ITs (T1–T3): an owner transfers (new owner promoted, old owner
 * downgraded to administrator, atomically); an administrator cannot transfer (owner-only); transfer
 * to a non-member is rejected (the documented choice: the new owner must already be a member).
 */
class TransferOwnershipIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team team() {
        return teams.save(new Team(UUID.randomUUID(), "Acme", "catalog", UUID.randomUUID()));
    }

    private void grant(Team team, User user, UUID roleId) {
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), roleId));
    }

    private UUID roleOf(Team team, User user) {
        return memberships.findByTeamIdAndUserId(team.getId(), user.getId())
                .orElseThrow().getRoleDefinitionId();
    }

    @Test
    void ownerTransfersToMember() {
        Team team = team();
        User owner = user("owner");
        User member = user("member");
        grant(team, owner, SystemRoles.OWNER_ID);
        grant(team, member, SystemRoles.MEMBER_ID);

        var transfer = rest.exchange(
                "/api/v1/teams/{t}/transfer-ownership",
                HttpMethod.POST,
                AbacTestConfig.as(
                        owner.getSubject(),
                        new TransferOwnershipRequest().newOwnerUserId(member.getId())),
                Void.class,
                team.getId());
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // New owner promoted; old owner downgraded to administrator — atomic.
        assertThat(roleOf(team, member)).isEqualTo(SystemRoles.OWNER_ID);
        assertThat(roleOf(team, owner)).isEqualTo(SystemRoles.ADMINISTRATOR_ID);
    }

    @Test
    void administratorCannotTransfer() {
        Team team = team();
        User owner = user("owner");
        User admin = user("admin");
        grant(team, owner, SystemRoles.OWNER_ID);
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        var transfer = rest.exchange(
                "/api/v1/teams/{t}/transfer-ownership",
                HttpMethod.POST,
                AbacTestConfig.as(
                        admin.getSubject(),
                        new TransferOwnershipRequest().newOwnerUserId(admin.getId())),
                String.class,
                team.getId());
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Unchanged: the owner is still the owner.
        assertThat(roleOf(team, owner)).isEqualTo(SystemRoles.OWNER_ID);
    }

    @Test
    void transferToNonMemberIsRejected() {
        Team team = team();
        User owner = user("owner");
        User outsider = user("outsider");
        grant(team, owner, SystemRoles.OWNER_ID);

        var transfer = rest.exchange(
                "/api/v1/teams/{t}/transfer-ownership",
                HttpMethod.POST,
                AbacTestConfig.as(
                        owner.getSubject(),
                        new TransferOwnershipRequest().newOwnerUserId(outsider.getId())),
                String.class,
                team.getId());
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Unchanged: the owner is still the owner (atomic — nothing half-applied).
        assertThat(roleOf(team, owner)).isEqualTo(SystemRoles.OWNER_ID);
    }
}
