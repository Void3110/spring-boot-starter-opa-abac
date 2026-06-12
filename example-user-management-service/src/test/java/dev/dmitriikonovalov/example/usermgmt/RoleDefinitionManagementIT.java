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

        // Create (level 25 senior tier, category tokens).
        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code("catalog-editor")
                        .roleLevel(25)
                        .permissions(Map.of("catalog", List.of("READ", "WRITE")))),
                RoleDefinition.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().getSystem()).isFalse();
        assertThat(create.getBody().getTeamId()).isEqualTo(team.getId());
        assertThat(create.getBody().getRoleLevel()).isEqualTo(25);

        // Update — U8: the explicit roleLevel overwrites an attributes-supplied role_level.
        var update = rest.exchange(
                "/api/v1/teams/{t}/role-definitions/{c}",
                HttpMethod.PUT,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionUpdate()
                        .roleLevel(20)
                        .attributes(Map.of("role_level", 40))
                        .permissions(Map.of("catalog", List.of("READ")))),
                RoleDefinition.class,
                team.getId(),
                "catalog-editor");
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getBody()).isNotNull();
        assertThat(update.getBody().getPermissions()).containsEntry("catalog", List.of("READ"));
        assertThat(update.getBody().getRoleLevel()).isEqualTo(20);
        assertThat(update.getBody().getAttributes()).containsEntry("role_level", 20);

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

    @Test // flipped from the pre-6.5 author-subset cell: the LEVEL CEILING is the bound now (U6/I5)
    void customRoleExceedingLevelCeilingIsDenied() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        // GRANT is only authorable at level 30 — a level-20 role granting it violates the ceiling.
        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code("superpower")
                        .roleLevel(20)
                        .permissions(Map.of("catalog", List.of("READ", "GRANT")))),
                String.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(create.getBody()).contains("ROLE_DEFINITION_INVALID");
    }

    @Test // I4 — the authoring round-trip: a senior-level role with a denial, read back whole
    void seniorRoleWithDenialRoundTrips() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionRequest()
                        .code("no-delete-senior")
                        .roleLevel(25)
                        .permissions(Map.of("catalog", List.of("READ", "WRITE", "TAG")))
                        .deniedActions(Map.of("catalog", List.of("delete")))),
                RoleDefinition.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().getRoleLevel()).isEqualTo(25);
        assertThat(create.getBody().getDeniedActions()).containsEntry("catalog", List.of("delete"));
        assertThat(create.getBody().getAttributes()).containsEntry("role_level", 25);

        // Read back via the list (the persisted row, not the create echo).
        var list = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.GET,
                AbacTestConfig.as(owner.getSubject()),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionPage.class,
                team.getId());
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getItems()).anyMatch(r ->
                r.getCode().equals("no-delete-senior")
                        && Integer.valueOf(25).equals(r.getRoleLevel())
                        && List.of("delete").equals(r.getDeniedActions().get("catalog")));
    }

    @Test // I5 — each authoring-contract violation answers 422 problem+json ROLE_DEFINITION_INVALID
    void contractViolationsAnswer422RoleDefinitionInvalid() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        record Cell(String name, RoleDefinitionRequest request) {}
        var cells = List.of(
                new Cell("missing roleLevel", new RoleDefinitionRequest()
                        .code("c1").permissions(Map.of("catalog", List.of("READ")))),
                new Cell("off-ladder level", new RoleDefinitionRequest()
                        .code("c2").roleLevel(15).permissions(Map.of("catalog", List.of("READ")))),
                new Cell("flat verb token", new RoleDefinitionRequest()
                        .code("c3").roleLevel(20).permissions(Map.of("catalog", List.of("read")))),
                new Cell("ceiling violation", new RoleDefinitionRequest()
                        .code("c4").roleLevel(10).permissions(Map.of("catalog", List.of("WRITE")))),
                new Cell("denial not a subset", new RoleDefinitionRequest()
                        .code("c5").roleLevel(20)
                        .permissions(Map.of("catalog", List.of("READ")))
                        .deniedActions(Map.of("catalog", List.of("delete")))));

        for (Cell cell : cells) {
            var response = rest.exchange(
                    "/api/v1/teams/{t}/role-definitions",
                    HttpMethod.POST,
                    AbacTestConfig.as(owner.getSubject(), cell.request()),
                    String.class,
                    team.getId());
            assertThat(response.getStatusCode()).as(cell.name()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody()).as(cell.name()).contains("ROLE_DEFINITION_INVALID");
            assertThat(response.getHeaders().getContentType().toString())
                    .as(cell.name()).contains("problem+json");
        }
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
                        .roleLevel(10)
                        .permissions(Map.of("catalog", List.of("READ")))),
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
                        .roleLevel(10)
                        .permissions(Map.of("catalog", List.of("READ")))),
                String.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Updating a system role → 409 (immutable).
        var update = rest.exchange(
                "/api/v1/teams/{t}/role-definitions/{c}",
                HttpMethod.PUT,
                AbacTestConfig.as(owner.getSubject(), new RoleDefinitionUpdate()
                        .roleLevel(10)
                        .permissions(Map.of("catalog", List.of("READ")))),
                String.class,
                team.getId(),
                SystemRoles.READER);
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
                        .roleLevel(10)
                        .permissions(Map.of("catalog", List.of("READ")))
                        .requiredTags(Map.of("region", List.of("emea")))
                        .matchMode(RoleDefinitionRequest.MatchModeEnum.ALL_OF)),
                RoleDefinition.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().getRequiredTags()).containsEntry("region", List.of("emea"));
        assertThat(create.getBody().getMatchMode())
                .isEqualTo(RoleDefinition.MatchModeEnum.ALL_OF);

        // Re-read via list (the 5.95 envelope) to confirm it persisted.
        var list = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.GET,
                AbacTestConfig.as(owner.getSubject()),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionPage.class,
                team.getId());
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getItems()).anyMatch(r ->
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
                        .roleLevel(10)
                        .permissions(Map.of("catalog", List.of("READ")))),
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
                        .roleLevel(20)
                        .permissions(Map.of("catalog", List.of("READ", "WRITE")))),
                RoleDefinition.class,
                team.getId());

        var list = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.GET,
                AbacTestConfig.as(owner.getSubject()),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionPage.class,
                team.getId());
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        // The five system roles + the one custom role (count is the envelope's exact total).
        assertThat(list.getBody().getCount()).isEqualTo(6);
        assertThat(list.getBody().getItems())
                .anyMatch(r -> r.getCode().equals("catalog-editor") && !r.getSystem());
        assertThat(list.getBody().getItems())
                .anyMatch(r -> r.getCode().equals(SystemRoles.OWNER) && r.getSystem());
    }
}
