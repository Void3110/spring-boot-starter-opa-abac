package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinition;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionUpdate;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Ticket-5 role-definition ITs (R1–R5): an owner defines/updates/deletes team-scoped custom roles
 * within the subset rule; an administrator cannot define roles (owner-only); system roles are
 * immutable; a defined custom role is assignable + resolvable.
 */
class RoleDefinitionManagementIT extends AbstractSecuredPostgresIT {

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
    void ownerCreatesUpdatesAndDeletesCustomRole() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        // Create (within the owner's read+write perms).
        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code("catalog-editor")
                        .attributes(Map.of("role_level", 25))
                        .permissions(Map.of("catalog", List.of("read", "write")))),
                RoleDefinition.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().getSystem()).isFalse();
        assertThat(create.getBody().getTeamId()).isEqualTo(team.getId());

        // Update.
        var update = rest.exchange(
                "/api/v1/teams/{t}/role-definitions/{c}",
                HttpMethod.PUT,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionUpdate()
                        .attributes(Map.of("role_level", 26))
                        .permissions(Map.of("catalog", List.of("read")))),
                RoleDefinition.class,
                team.getId(),
                "catalog-editor");
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getBody()).isNotNull();
        assertThat(update.getBody().getPermissions()).containsEntry("catalog", List.of("read"));

        // Delete.
        var delete = rest.exchange(
                "/api/v1/teams/{t}/role-definitions/{c}",
                HttpMethod.DELETE,
                AbacTestConfig.as(owner.getSubject()),
                Void.class,
                team.getId(),
                "catalog-editor");
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(roles.findByTeamIdAndCode(team.getId(), "catalog-editor")).isEmpty();
    }

    @Test
    void customRoleExceedingOwnPermsIsDenied() {
        Team team = team();
        // An owner's system perms are {"*": [read, write]} → "delete" is beyond them.
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code("superpower")
                        .permissions(Map.of("catalog", List.of("read", "write", "delete")))),
                String.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void administratorCannotDefineRoles() {
        Team team = team();
        User admin = user("admin");
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(admin.getSubject(), new RoleDefinitionRequest()
                        .code("whatever")
                        .permissions(Map.of("catalog", List.of("read")))),
                String.class,
                team.getId());
        // define-roles is owner-only (not in the admin's management ladder).
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void editingASystemRoleCodeIsRejected() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        // Creating a custom role with a reserved system code → 409.
        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code(SystemRoles.OWNER)
                        .permissions(Map.of("catalog", List.of("read")))),
                String.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Updating a system role → 409 (immutable).
        var update = rest.exchange(
                "/api/v1/teams/{t}/role-definitions/{c}",
                HttpMethod.PUT,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionUpdate()
                        .permissions(Map.of("catalog", List.of("read")))),
                String.class,
                team.getId(),
                SystemRoles.VIEWER);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test // RD1 — requiredTags + matchMode persist on a custom role and round-trip through the API
    void customRoleCarriesRequiredTags() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code("regional-reader")
                        .permissions(Map.of("catalog", List.of("read")))
                        .requiredTags(Map.of("region", List.of("emea")))
                        .matchMode(RoleDefinitionRequest.MatchModeEnum.ALL_OF)),
                RoleDefinition.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().getRequiredTags()).containsEntry("region", List.of("emea"));
        assertThat(create.getBody().getMatchMode())
                .isEqualTo(RoleDefinition.MatchModeEnum.ALL_OF);

        // Re-read via list to confirm it persisted.
        var list = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.GET,
                AbacTestConfig.as(owner.getSubject()),
                RoleDefinition[].class,
                team.getId());
        assertThat(list.getBody()).anyMatch(r ->
                r.getCode().equals("regional-reader")
                        && r.getRequiredTags().containsKey("region")
                        && r.getMatchMode() == RoleDefinition.MatchModeEnum.ALL_OF);
    }

    @Test // RD3 — a role with no requiredTags keeps the prior shape (empty map, null mode)
    void customRoleWithoutRequiredTagsHasEmptyRequirement() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code("plain-reader")
                        .permissions(Map.of("catalog", List.of("read")))),
                RoleDefinition.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().getRequiredTags()).isNullOrEmpty();
        assertThat(create.getBody().getMatchMode()).isNull();
    }

    @Test
    void listReturnsSystemAndCustomRoles() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code("catalog-editor")
                        .permissions(Map.of("catalog", List.of("read", "write")))),
                RoleDefinition.class,
                team.getId());

        var list = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.GET,
                AbacTestConfig.as(owner.getSubject()),
                RoleDefinition[].class,
                team.getId());
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        // The four system roles + the one custom role.
        assertThat(list.getBody()).anyMatch(r -> r.getCode().equals("catalog-editor") && !r.getSystem());
        assertThat(list.getBody()).anyMatch(r -> r.getCode().equals(SystemRoles.OWNER) && r.getSystem());
    }
}
