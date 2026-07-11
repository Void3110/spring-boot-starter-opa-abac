package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
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
    @Autowired private UserRepository users;

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

    // I4 (DIRECTORY-QUERY-FILTERS) — ensureUser is an upsert of displayName only: a re-seed with a
    // changed name converges on the SAME row (same userId, new name, no duplicate); an identical
    // re-post is a no-op on the same row.
    @Test
    void ensureUserUpsertsDisplayNameOnRerun() {
        String subject = "sub-upsert-" + UUID.randomUUID();

        var first = rest.postForEntity(
                "/internal/bootstrap/users", Map.of("subject", subject, "displayName", "A"), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object userId = first.getBody().get("userId");
        assertThat(users.findBySubject(subject).orElseThrow().getDisplayName()).isEqualTo("A");

        var second = rest.postForEntity(
                "/internal/bootstrap/users", Map.of("subject", subject, "displayName", "B"), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("userId")).isEqualTo(userId); // same row, never a duplicate
        var updated = users.findBySubject(subject).orElseThrow();
        assertThat(updated.getId().toString()).isEqualTo(userId);
        assertThat(updated.getDisplayName()).isEqualTo("B"); // the upsert took

        var third = rest.postForEntity(
                "/internal/bootstrap/users", Map.of("subject", subject, "displayName", "B"), Map.class);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(third.getBody().get("userId")).isEqualTo(userId); // idempotent no-op
        assertThat(users.findBySubject(subject).orElseThrow().getDisplayName()).isEqualTo("B");
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
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("ROLE_DEFINITION_INVALID");
        assertThat(roles.findByTeamIdAndCode(team.getId(), "boot-stale")).isEmpty();
    }
}
