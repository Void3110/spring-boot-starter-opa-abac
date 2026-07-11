package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The batch resolve endpoint (Slice 7.3, ADR 0024 — QA case I1), real Postgres:
 * {@code GET /internal/effective-roles} answers {@code 200} with <strong>exactly one entry per
 * requested target</strong> — the resolved role or an explicit {@code null} (never {@code 204}) —
 * and {@code 400} for every structurally invalid request (the client classifies a 4xx as a
 * <em>permanent</em> whole-batch outage). Internal API → no auth needed.
 */
class EffectiveRoleBatchResolveIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team teamFor(UUID targetId) {
        return teams.save(new Team(UUID.randomUUID(), "Acme", "catalog", targetId));
    }

    @Test // I1 — mixed targets: one resolved via real membership, one authoritative no-role
    void mixedBatchAnswersOneEntryPerTargetWithExplicitNull() {
        UUID governed = UUID.randomUUID();
        UUID ungoverned = UUID.randomUUID();
        Team team = teamFor(governed);
        User member = user("batch-member");
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), member.getId(),
                SystemRoles.OWNER_ID));

        ResponseEntity<JsonNode> response = rest.getForEntity(
                "/internal/effective-roles?userId=" + member.getSubject()
                        + "&target=catalog:" + governed
                        + "&target=catalog:" + ungoverned,
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); // never 204
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).as("exactly one entry per requested target").isEqualTo(2);

        JsonNode governedEntry = entryFor(body, governed);
        assertThat(governedEntry.get("role").isNull()).isFalse();
        assertThat(governedEntry.get("role").get("code").asText()).isEqualTo("owner");

        JsonNode ungovernedEntry = entryFor(body, ungoverned);
        assertThat(ungovernedEntry.has("role")).as("no-role travels as an EXPLICIT null").isTrue();
        assertThat(ungovernedEntry.get("role").isNull()).isTrue();
    }

    @Test // I1 — an unknown subject is an authoritative no-role on every entry (not an error)
    void unknownSubjectAnswersNullRoles() {
        UUID target = UUID.randomUUID();

        ResponseEntity<JsonNode> response = rest.getForEntity(
                "/internal/effective-roles?userId=sub-nobody&target=catalog:" + target,
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().size()).isEqualTo(1);
        assertThat(response.getBody().get(0).get("role").isNull()).isTrue();
    }

    @Test // I1 — malformed targets → 400 (a permanent outage client-side)
    void malformedTargetsAnswer400() {
        assertThat(rest.getForEntity(
                "/internal/effective-roles?userId=sub-x&target=no-colon", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(rest.getForEntity(
                "/internal/effective-roles?userId=sub-x&target=catalog:not-a-uuid", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(rest.getForEntity(
                "/internal/effective-roles?userId=sub-x&target=:" + UUID.randomUUID(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test // I1 — a duplicate target would make one-entry-per-target ambiguous → 400
    void duplicateTargetAnswers400() {
        UUID target = UUID.randomUUID();

        assertThat(rest.getForEntity(
                "/internal/effective-roles?userId=sub-x&target=catalog:" + target
                        + "&target=catalog:" + target,
                JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test // I1 — a missing userId / missing target param → 400
    void missingParamsAnswer400() {
        assertThat(rest.getForEntity(
                "/internal/effective-roles?target=catalog:" + UUID.randomUUID(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(rest.getForEntity(
                "/internal/effective-roles?userId=sub-x", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static JsonNode entryFor(JsonNode body, UUID resourceId) {
        for (JsonNode entry : body) {
            if (resourceId.toString().equals(entry.get("resourceId").asText())) {
                return entry;
            }
        }
        throw new AssertionError("no entry for " + resourceId + " in " + body);
    }
}
