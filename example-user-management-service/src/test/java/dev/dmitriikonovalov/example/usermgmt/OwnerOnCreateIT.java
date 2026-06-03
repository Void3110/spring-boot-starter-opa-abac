package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.TeamService;
import dev.dmitriikonovalov.example.usermgmt.service.TeamTargetExistsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Ticket-3 owner-on-create ITs (O1–O4) against real Postgres. The atomicity test (O2) spies the
 * membership repository to fail the second write <em>inside</em> the {@code @Transactional} boundary
 * and asserts the team write rolled back too — proving the bootstrap is one unit of work.
 */
class OwnerOnCreateIT extends AbstractPostgresIT {

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private UserRepository users;

    @MockitoSpyBean
    private TeamMembershipRepository memberships;

    private UUID newUser() {
        return users.save(new User(UUID.randomUUID(), "sub-" + UUID.randomUUID(), "Creator")).getId();
    }

    @Test
    void createWithOwnerYieldsOneTeamAndOneOwnerMembership() {
        UUID creator = newUser();
        UUID targetId = UUID.randomUUID();

        var team = teamService.createWithOwner(creator, "Acme", "catalog", targetId);

        assertThat(teams.findById(team.getId())).isPresent();
        var teamMemberships = memberships.findByTeamId(team.getId());
        assertThat(teamMemberships).hasSize(1);
        TeamMembership owner = teamMemberships.get(0);
        assertThat(owner.getUserId()).isEqualTo(creator);
        assertThat(owner.getRoleDefinitionId()).isEqualTo(SystemRoles.OWNER_ID);
    }

    @Test
    void forcedFailureMidCreatePersistsNothing() {
        UUID creator = newUser();
        UUID targetId = UUID.randomUUID();
        long teamsBefore = teams.count();

        // Fail the membership write that happens after the team is saved, within the same tx.
        doThrow(new RuntimeException("boom"))
                .when(memberships).save(any(TeamMembership.class));

        assertThatThrownBy(() -> teamService.createWithOwner(creator, "Acme", "catalog", targetId))
                .isInstanceOf(RuntimeException.class);

        // Nothing was persisted: the team write rolled back with the failed membership write.
        assertThat(teams.count()).isEqualTo(teamsBefore);
        assertThat(teams.findByTargetTypeAndTargetId("catalog", targetId)).isEmpty();
    }

    @Test
    void oneTeamPerTeamTarget() {
        UUID creator = newUser();
        UUID targetId = UUID.randomUUID();
        teamService.createWithOwner(creator, "Acme", "catalog", targetId);

        assertThatThrownBy(() -> teamService.createWithOwner(creator, "Acme 2", "catalog", targetId))
                .isInstanceOf(TeamTargetExistsException.class);
    }

    @Test
    void unknownCreatorIsRejected() {
        assertThatThrownBy(() ->
                        teamService.createWithOwner(UUID.randomUUID(), "Acme", "catalog", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
