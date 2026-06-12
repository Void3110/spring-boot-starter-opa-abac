package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.CreateTeamRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.User;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.UserRequest;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Error-contract integration tests (Phase 5.9 T3) — QA cases I4, I4b, I5, I6, I6-201 — through the real
 * secured chain over a real Postgres (random port; subject via the {@link AbacTestConfig} header), asserting
 * the RFC-7807 {@code application/problem+json} body + the typed {@code errorCode} per status, and the
 * {@code Location} header on a {@code 201}.
 *
 * <p>I5b (the 422 subset-rule body) is exercised in {@link MembershipManagementIT}'s subset path; the
 * remaining 409 refinements are asserted in the e2e (T4). 403 is genuinely driven here: a subject with no
 * team role calling a gated team mutation is denied by the in-process OPA client (no role definition →
 * deny), so the inherited {@code AccessDeniedException} → {@code ACCESS_DENIED} mapping renders a problem
 * body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorContractIT extends AbstractPostgresIT {

    private static final String SUBJECT = "it-error-contract";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    // I4 — GET an unknown user → 404 problem+json with RESOURCE_NOT_FOUND.
    @Test
    void notFoundIsProblemJsonWithResourceNotFound() throws Exception {
        var response = rest.exchange(
                "/api/v1/users/{id}", HttpMethod.GET, AbacTestConfig.as(SUBJECT), String.class,
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertProblem(response.getHeaders().getContentType());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.has("message")).isFalse(); // clean replacement
        assertThat(body.get("type").asText()).isEqualTo("/problems/resource-not-found");
    }

    // I4b — a malformed create body (blank required subject) → 400 problem+json VALIDATION_FAILED.
    @Test
    void validationFailureIsProblemJsonWithValidationFailed() throws Exception {
        var request = new UserRequest().subject("").displayName("");
        var response = rest.exchange(
                "/api/v1/users", HttpMethod.POST, AbacTestConfig.as(SUBJECT, request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertProblem(response.getHeaders().getContentType());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.has("message")).isFalse();
    }

    // I5 — creating a second team for the same (targetType, targetId) → 409 TEAM_TARGET_EXISTS.
    @Test
    void duplicateTeamTargetIsProblemJsonWithTeamTargetExists() throws Exception {
        UUID creator = createUser();
        UUID targetId = UUID.randomUUID();
        var first = new CreateTeamRequest().name("Acme").targetType("catalog").targetId(targetId)
                .creatorUserId(creator);
        rest.exchange("/api/v1/teams", HttpMethod.POST, AbacTestConfig.as(SUBJECT, first), String.class);

        var second = new CreateTeamRequest().name("Acme 2").targetType("catalog").targetId(targetId)
                .creatorUserId(creator);
        var response = rest.exchange(
                "/api/v1/teams", HttpMethod.POST, AbacTestConfig.as(SUBJECT, second), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertProblem(response.getHeaders().getContentType());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("TEAM_TARGET_EXISTS");
        assertThat(body.get("status").asInt()).isEqualTo(409);
    }

    // I5c — deleting a custom role still bound to a member → 409 STATE_CONFLICT problem+json (the
    // team_membership FK conflict is a client-resolvable state conflict, not a 500; retro-audit
    // follow-up, mapped by the starter's PersistenceConflictProblemAdvice).
    @Test
    void deletingAnInUseCustomRoleIsProblemJsonWithStateConflict() throws Exception {
        String ownerSubject = "kc-" + UUID.randomUUID();
        UUID ownerId = createUser(ownerSubject);
        UUID memberId = createUser("kc-" + UUID.randomUUID());

        var teamRequest = new CreateTeamRequest().name("Conflict").targetType("catalog")
                .targetId(UUID.randomUUID()).creatorUserId(ownerId);
        var team = rest.exchange(
                "/api/v1/teams", HttpMethod.POST, AbacTestConfig.as(SUBJECT, teamRequest),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.Team.class);
        UUID teamId = team.getBody().getId();

        var roleRequest = new dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionRequest()
                .code("in-use-role")
                .roleLevel(10)
                .permissions(java.util.Map.of("catalog", java.util.List.of("READ")));
        var role = rest.exchange(
                "/api/v1/teams/{t}/role-definitions", HttpMethod.POST,
                AbacTestConfig.as(ownerSubject, roleRequest), String.class, teamId);
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var add = rest.exchange(
                "/api/v1/teams/{t}/members", HttpMethod.POST,
                AbacTestConfig.as(ownerSubject,
                        new dev.dmitriikonovalov.example.usermgmt.openapi.model.AddMemberRequest()
                                .userId(memberId).roleCode("in-use-role")),
                String.class, teamId);
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var del = rest.exchange(
                "/api/v1/teams/{t}/role-definitions/{code}", HttpMethod.DELETE,
                AbacTestConfig.as(ownerSubject), String.class, teamId, "in-use-role");

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertProblem(del.getHeaders().getContentType());
        JsonNode body = MAPPER.readTree(del.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("STATE_CONFLICT");
        assertThat(body.get("status").asInt()).isEqualTo(409);
    }

    // I6 — a subject with no team role calling a gated mutation → 403 ACCESS_DENIED problem+json.
    @Test
    void deniedGatedCallIsProblemJsonWithAccessDenied() throws Exception {
        // A team the caller has no membership on → the supplier returns no role-def → OPA denies.
        var response = rest.exchange(
                "/api/v1/teams/{teamId}/members", HttpMethod.GET,
                AbacTestConfig.as("stranger-" + UUID.randomUUID()), String.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertProblem(response.getHeaders().getContentType());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(body.get("status").asInt()).isEqualTo(403);
    }

    // I6-201 — a successful create carries Location: /api/v1/users/<id> matching the created id.
    @Test
    void createCarriesLocationHeader() {
        var request = new UserRequest().subject("kc-" + UUID.randomUUID()).displayName("Alice");
        var response = rest.exchange(
                "/api/v1/users", HttpMethod.POST, AbacTestConfig.as(SUBJECT, request), User.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = response.getBody().getId();
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/api/v1/users/" + id);
    }

    private UUID createUser() {
        return createUser("kc-" + UUID.randomUUID());
    }

    private UUID createUser(String subject) {
        var request = new UserRequest().subject(subject).displayName("Creator");
        var created = rest.exchange(
                "/api/v1/users", HttpMethod.POST, AbacTestConfig.as(SUBJECT, request), User.class);
        return created.getBody().getId();
    }

    private static void assertProblem(MediaType contentType) {
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
    }
}
