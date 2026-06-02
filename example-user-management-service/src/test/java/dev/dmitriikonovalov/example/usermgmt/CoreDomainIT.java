package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Ticket-2 domain ITs against real Postgres: the seeded system roles (D2), JSONB round-trip of
 * {@code permissions}/{@code attributes} (D3), and the {@code (team_id, user_id)} unique constraint
 * on memberships (D4). D1 (schema present + {@code ddl-auto: validate} clean) is implicit in the
 * context booting at all.
 */
class CoreDomainIT extends AbstractPostgresIT {

    @Autowired
    private RoleDefinitionRepository roles;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private UserRepository users;

    @Autowired
    private TeamMembershipRepository memberships;

    @Test
    void systemRolesAreSeeded() {
        List<RoleDefinitionEntity> systemRoles = roles.findBySystemTrue();
        assertThat(systemRoles).hasSize(4);
        assertThat(systemRoles).allSatisfy(r -> {
            assertThat(r.isSystem()).isTrue();
            assertThat(r.getTeamId()).isNull();
        });
        assertThat(systemRoles.stream().map(RoleDefinitionEntity::getCode))
                .containsExactlyInAnyOrder(
                        SystemRoles.OWNER,
                        SystemRoles.ADMINISTRATOR,
                        SystemRoles.MEMBER,
                        SystemRoles.VIEWER);
    }

    @Test
    void systemRoleSeedIdsAndPermissionsAreStable() {
        RoleDefinitionEntity owner = roles.findBySystemTrueAndCode(SystemRoles.OWNER).orElseThrow();
        assertThat(owner.getId()).isEqualTo(SystemRoles.OWNER_ID);
        assertThat(owner.getPermissions()).containsEntry("*", List.of("read", "write"));

        RoleDefinitionEntity viewer = roles.findBySystemTrueAndCode(SystemRoles.VIEWER).orElseThrow();
        assertThat(viewer.getId()).isEqualTo(SystemRoles.VIEWER_ID);
        assertThat(viewer.getPermissions()).containsEntry("*", List.of("read"));
    }

    @Test
    void permissionsAndAttributesRoundTripThroughJsonb() {
        var role = new RoleDefinitionEntity(
                UUID.randomUUID(),
                "catalog-editor",
                false,
                null,
                Map.of("role_level", 25),
                Map.of("catalog", List.of("read", "write")));
        roles.save(role);

        var reloaded = roles.findById(role.getId()).orElseThrow();
        assertThat(reloaded.getPermissions()).containsEntry("catalog", List.of("read", "write"));
        assertThat(reloaded.getAttributes()).containsEntry("role_level", 25);
    }

    @Test
    void teamMembershipIsUniquePerTeamAndUser() {
        var team = teams.save(new Team(UUID.randomUUID(), "Acme", "catalog", UUID.randomUUID()));
        var user = users.save(new User(UUID.randomUUID(), "sub-" + UUID.randomUUID(), "Alice"));
        UUID ownerRoleId = SystemRoles.OWNER_ID;

        memberships.saveAndFlush(
                new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), ownerRoleId));

        assertThatThrownBy(() -> memberships.saveAndFlush(
                        new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), ownerRoleId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
