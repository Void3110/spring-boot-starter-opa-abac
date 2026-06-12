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

    @Test // I1 — the five-tier seed ladder (Phase 6.5)
    void systemRolesAreSeeded() {
        List<RoleDefinitionEntity> systemRoles = roles.findBySystemTrue();
        assertThat(systemRoles).hasSize(5);
        assertThat(systemRoles).allSatisfy(r -> {
            assertThat(r.isSystem()).isTrue();
            assertThat(r.getTeamId()).isNull();
            // I1 — every seed row carries an empty denied_actions (nothing withheld).
            assertThat(r.getDeniedActions()).isEmpty();
        });
        assertThat(systemRoles.stream().map(RoleDefinitionEntity::getCode))
                .containsExactlyInAnyOrder(
                        SystemRoles.OWNER,
                        SystemRoles.ADMINISTRATOR,
                        SystemRoles.SENIOR,
                        SystemRoles.MEMBER,
                        SystemRoles.READER);
    }

    @Test // I1 — stable ids, the category vocabulary, and the ladder levels
    void systemRoleSeedIdsAndPermissionsAreStable() {
        RoleDefinitionEntity owner = roles.findBySystemTrueAndCode(SystemRoles.OWNER).orElseThrow();
        assertThat(owner.getId()).isEqualTo(SystemRoles.OWNER_ID);
        assertThat(owner.getPermissions())
                .containsEntry("*", List.of("READ", "WRITE", "TAG", "GRANT"));
        assertThat(owner.getAttributes()).containsEntry("role_level", 40);

        RoleDefinitionEntity admin =
                roles.findBySystemTrueAndCode(SystemRoles.ADMINISTRATOR).orElseThrow();
        assertThat(admin.getId()).isEqualTo(SystemRoles.ADMINISTRATOR_ID);
        assertThat(admin.getPermissions())
                .containsEntry("*", List.of("READ", "WRITE", "TAG", "GRANT"));
        assertThat(admin.getAttributes()).containsEntry("role_level", 30);

        // The NEW senior tier (25) — between member and administrator.
        RoleDefinitionEntity senior = roles.findBySystemTrueAndCode(SystemRoles.SENIOR).orElseThrow();
        assertThat(senior.getId()).isEqualTo(SystemRoles.SENIOR_ID);
        assertThat(senior.getPermissions()).containsEntry("*", List.of("READ", "WRITE", "TAG"));
        assertThat(senior.getAttributes()).containsEntry("role_level", 25);

        RoleDefinitionEntity member = roles.findBySystemTrueAndCode(SystemRoles.MEMBER).orElseThrow();
        assertThat(member.getId()).isEqualTo(SystemRoles.MEMBER_ID);
        assertThat(member.getPermissions()).containsEntry("*", List.of("READ", "WRITE", "TAG"));
        assertThat(member.getAttributes()).containsEntry("role_level", 20);

        // viewer renamed to reader (same id — membership FKs untouched), level 10.
        RoleDefinitionEntity reader = roles.findBySystemTrueAndCode(SystemRoles.READER).orElseThrow();
        assertThat(reader.getId()).isEqualTo(SystemRoles.READER_ID);
        assertThat(reader.getPermissions()).containsEntry("*", List.of("READ"));
        assertThat(reader.getAttributes()).containsEntry("role_level", 10);
        assertThat(roles.findBySystemTrueAndCode("viewer")).isEmpty();
    }

    @Test
    void permissionsAndAttributesRoundTripThroughJsonb() {
        var role = new RoleDefinitionEntity(
                UUID.randomUUID(),
                "catalog-editor",
                false,
                null,
                Map.of("role_level", 25),
                Map.of("catalog", List.of("READ", "WRITE")));
        role.setDeniedActions(Map.of("catalog", List.of("delete")));
        roles.save(role);

        var reloaded = roles.findById(role.getId()).orElseThrow();
        assertThat(reloaded.getPermissions()).containsEntry("catalog", List.of("READ", "WRITE"));
        assertThat(reloaded.getAttributes()).containsEntry("role_level", 25);
        // I1 — denied_actions rides through the jsonb mapping.
        assertThat(reloaded.getDeniedActions()).containsEntry("catalog", List.of("delete"));
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
