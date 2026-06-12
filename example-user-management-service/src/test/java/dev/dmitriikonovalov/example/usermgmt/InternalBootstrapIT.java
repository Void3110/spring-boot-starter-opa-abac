package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

/**
 * The bootstrap custom-role seam (Phase 6.5): it routes through {@code RoleDefinitionService}, so the
 * authoring contract applies to the e2e rig's seeding too — the bootstrap is <b>not</b> a validation
 * bypass — and a re-run converges (idempotent upsert).
 */
class InternalBootstrapIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private RoleDefinitionRepository roles;

    private Team team() {
        return teams.save(new Team(UUID.randomUUID(), "Boot", "catalog", UUID.randomUUID()));
    }

    @Test
    void ensureCustomRoleCreatesAndConvergesOnRerun() {
        Team team = team();
        Map<String, Object> body = Map.of(
                "teamId", team.getId(),
                "code", "boot-editor",
                "roleLevel", 20,
                "permissions", Map.of("catalog", List.of("READ", "WRITE")),
                "deniedActions", Map.of("catalog", List.of("delete")));

        var first = rest.postForEntity("/internal/bootstrap/custom-roles", body, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        var second = rest.postForEntity("/internal/bootstrap/custom-roles", body, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("roleId")).isEqualTo(first.getBody().get("roleId"));

        var stored = roles.findByTeamIdAndCode(team.getId(), "boot-editor").orElseThrow();
        assertThat(stored.getPermissions()).containsEntry("catalog", List.of("READ", "WRITE"));
        assertThat(stored.getDeniedActions()).containsEntry("catalog", List.of("delete"));
        assertThat(stored.getAttributes()).containsEntry("role_level", 20);
    }

    @Test // the no-bypass pin: an invalid payload answers 422 exactly like the management API
    void ensureCustomRoleEnforcesTheAuthoringContract() {
        Team team = team();
        Map<String, Object> flatToken = Map.of(
                "teamId", team.getId(),
                "code", "boot-stale",
                "roleLevel", 20,
                "permissions", Map.of("catalog", List.of("read")));

        var response = rest.postForEntity("/internal/bootstrap/custom-roles", flatToken, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("ROLE_DEFINITION_INVALID");
        assertThat(roles.findByTeamIdAndCode(team.getId(), "boot-stale")).isEmpty();
    }
}
