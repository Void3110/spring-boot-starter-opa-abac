package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.CreateTeamRequest;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Slice B4 T7 — the target-squatting gate on the PUBLIC {@code POST /api/v1/teams} (I6–I9), against real
 * Postgres + the secured chain. The ownership decision is driven by {@link AbacTestConfig#ownershipDecision}
 * (a test seam over the {@code ResourceOwnershipResolver}); the {@code /internal/bootstrap/teams} seed path
 * is a separate controller that never hits the gate, so it bypasses by construction (I9).
 */
class OwnershipGateIT extends AbstractSecuredPostgresIT {

    private static final String ALICE = "sub-alice-owner";

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;

    @AfterEach
    void resetOwnership() {
        AbacTestConfig.resetOwnership(); // back to owner-of-everything for the other suites
    }

    private void ensureUser(String subject) {
        if (users.findBySubject(subject).isEmpty()) {
            users.save(new User(UUID.randomUUID(), subject, subject));
        }
    }

    private CreateTeamRequest createTeamBody(UUID targetId) {
        CreateTeamRequest body = new CreateTeamRequest();
        body.setName("Alice Co");
        body.setTargetType("catalog");
        body.setTargetId(targetId);
        return body;
    }

    @Test // I6 — createTeam on a catalog the caller OWNS → 201
    void ownerCreatesTeam() {
        ensureUser(ALICE);
        UUID catalog = UUID.randomUUID();
        AbacTestConfig.ownershipDecision = (subject, type, id) ->
                subject.equals(ALICE) && type.equals("catalog") && id.equals(catalog);

        var res = rest.exchange(
                "/api/v1/teams", HttpMethod.POST,
                AbacTestConfig.as(ALICE, createTeamBody(catalog)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(teams.findByTargetTypeAndTargetId("catalog", catalog)).isPresent();
    }

    @Test // I7 — createTeam on a catalog the caller does NOT own → 403 (squatting closed)
    void nonOwnerIsDenied() {
        ensureUser(ALICE);
        UUID someoneElsesCatalog = UUID.randomUUID();
        AbacTestConfig.ownershipDecision = (subject, type, id) -> false; // not the owner

        var res = rest.exchange(
                "/api/v1/teams", HttpMethod.POST,
                AbacTestConfig.as(ALICE, createTeamBody(someoneElsesCatalog)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        // No team was bound — the squat did not persist.
        assertThat(teams.findByTargetTypeAndTargetId("catalog", someoneElsesCatalog)).isEmpty();
    }

    @Test // I8 — the resolver cannot verify (owning service down → isOwner false) → 403 (fail-closed)
    void unverifiableOwnershipIsDenied() {
        ensureUser(ALICE);
        UUID catalog = UUID.randomUUID();
        // An unverifiable check is, at the resolver boundary, indistinguishable from "not owner": the
        // DiscoveryOwnershipResolver returns false on an outage/404 (proven in T5 U8). Here that surfaces
        // as isOwner=false → 403, exactly like a non-owner.
        AbacTestConfig.ownershipDecision = (subject, type, id) -> false;

        var res = rest.exchange(
                "/api/v1/teams", HttpMethod.POST,
                AbacTestConfig.as(ALICE, createTeamBody(catalog)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(teams.findByTargetTypeAndTargetId("catalog", catalog)).isEmpty();
    }

    @Test // I9 — the /internal/bootstrap/teams seed path BYPASSES the gate (still creates) even when the
    // ownership decision would deny — a separate controller that never reaches createTeam.
    void bootstrapPathBypassesTheGate() {
        UUID catalog = UUID.randomUUID();
        AbacTestConfig.ownershipDecision = (subject, type, id) -> false; // would deny the public path

        var body = new InternalEnsureTeam("Seed Team", "catalog", catalog);
        var res = rest.postForEntity("/internal/bootstrap/teams", body, JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(teams.findByTargetTypeAndTargetId("catalog", catalog)).isPresent();
    }

    /** Mirrors InternalBootstrapController.EnsureTeam for the I9 request body. */
    private record InternalEnsureTeam(String name, String targetType, UUID targetId) {}
}
