package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.UserPage;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The 5.95 list-envelope contract over the user-service's lists (QA cases I7–I8): envelope members +
 * defaults on a representative pair — one top-level list ({@code /users}) and one team-scoped list
 * ({@code /teams/{t}/role-definitions}) — plus one strict param negative ({@code 400 VALIDATION_FAILED}
 * {@code problem+json}) and one past-the-end ({@code 200} + empty {@code items} + the exact
 * {@code count}). Runs the real secured chain (the dogfooded {@code @OpaPreAuthorize} path) against
 * real Postgres. I9 — the {@code /internal/**} unpaginated-by-design note — is a definition-site
 * comment in {@code SecurityConfig}, not a runtime case.
 */
class PaginationEnvelopeIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;

    // I7 — the top-level users list: defaults applied (page=0, perPage=20), all envelope members present.
    // (The shared container accumulates rows across ITs, so the count is asserted as a lower bound.)
    @Test
    void envelopeAndDefaults_onUsersList() {
        User created = users.save(
                new User(UUID.randomUUID(), "sub-page-" + UUID.randomUUID(), "Paged"));

        var list = rest.exchange(
                "/api/v1/users?perPage=100",
                HttpMethod.GET,
                AbacTestConfig.as("it-pagination-subject"),
                UserPage.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getCount()).isGreaterThanOrEqualTo(1);
        assertThat(list.getBody().getPage()).isZero();
        assertThat(list.getBody().getPerPage()).isEqualTo(100);

        // Presence via the exact-match ?subject filter: the plain list has no ordering contract, so
        // "the new row is inside the first 100" only held while the shared container had <100
        // accumulated users — an execution-order dependence, not an API property.
        var found = rest.exchange(
                "/api/v1/users?subject=" + created.getSubject(),
                HttpMethod.GET,
                AbacTestConfig.as("it-pagination-subject"),
                UserPage.class);
        assertThat(found.getBody()).isNotNull();
        assertThat(found.getBody().getItems()).anyMatch(u -> u.getId().equals(created.getId()));

        // The defaults: no params → page=0, perPage=20.
        var defaults = rest.exchange(
                "/api/v1/users",
                HttpMethod.GET,
                AbacTestConfig.as("it-pagination-subject"),
                UserPage.class);
        assertThat(defaults.getBody()).isNotNull();
        assertThat(defaults.getBody().getPage()).isZero();
        assertThat(defaults.getBody().getPerPage()).isEqualTo(20);
        assertThat(defaults.getBody().getItems()).hasSizeLessThanOrEqualTo(20);
    }

    // I7 — the team-scoped role-definitions list: a fresh team sees exactly the 4 system roles; an
    // explicit perPage=2 window slices with the exact count intact.
    @Test
    void envelopeAndExactCount_onTeamScopedRolesList() {
        Team team = teams.save(new Team(UUID.randomUUID(), "Paging", "catalog", UUID.randomUUID()));
        User owner = users.save(
                new User(UUID.randomUUID(), "sub-owner-" + UUID.randomUUID(), "Owner"));
        memberships.save(
                new TeamMembership(UUID.randomUUID(), team.getId(), owner.getId(), SystemRoles.OWNER_ID));

        var full = rest.exchange(
                "/api/v1/teams/{t}/role-definitions",
                HttpMethod.GET,
                AbacTestConfig.as(owner.getSubject()),
                RoleDefinitionPage.class,
                team.getId());
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getBody()).isNotNull();
        assertThat(full.getBody().getCount()).isEqualTo(5); // the five system roles
        assertThat(full.getBody().getPage()).isZero();
        assertThat(full.getBody().getPerPage()).isEqualTo(20);
        assertThat(full.getBody().getItems()).hasSize(5);

        var window = rest.exchange(
                "/api/v1/teams/{t}/role-definitions?page=1&perPage=2",
                HttpMethod.GET,
                AbacTestConfig.as(owner.getSubject()),
                RoleDefinitionPage.class,
                team.getId());
        assertThat(window.getBody()).isNotNull();
        assertThat(window.getBody().getCount()).isEqualTo(5); // exact count survives the window
        assertThat(window.getBody().getPage()).isEqualTo(1);
        assertThat(window.getBody().getPerPage()).isEqualTo(2);
        assertThat(window.getBody().getItems()).hasSize(2);
    }

    // I8 — one strict negative: perPage=101 → 400 problem+json VALIDATION_FAILED (no clamping).
    @Test
    void boundsViolation_is400ValidationFailed() {
        var response = rest.exchange(
                "/api/v1/users?perPage=101",
                HttpMethod.GET,
                AbacTestConfig.as("it-pagination-subject"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/problem+json");
        assertThat(response.getBody()).contains("\"errorCode\":\"VALIDATION_FAILED\"");
    }

    // I8 — past-the-end: 200 + empty items + the exact count (never a 404).
    @Test
    void pastTheEnd_is200EmptyWithExactCount() {
        Team team = teams.save(new Team(UUID.randomUUID(), "PagingEnd", "catalog", UUID.randomUUID()));
        User owner = users.save(
                new User(UUID.randomUUID(), "sub-owner-" + UUID.randomUUID(), "Owner"));
        memberships.save(
                new TeamMembership(UUID.randomUUID(), team.getId(), owner.getId(), SystemRoles.OWNER_ID));

        var page = rest.exchange(
                "/api/v1/teams/{t}/role-definitions?page=9&perPage=2",
                HttpMethod.GET,
                AbacTestConfig.as(owner.getSubject()),
                RoleDefinitionPage.class,
                team.getId());

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).isNotNull();
        assertThat(page.getBody().getCount()).isEqualTo(5);
        assertThat(page.getBody().getPage()).isEqualTo(9);
        assertThat(page.getBody().getItems()).isEmpty();
    }
}
