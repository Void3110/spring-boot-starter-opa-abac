package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TeamPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.UserPage;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The DIRECTORY-QUERY-FILTERS lookups through the real HTTP + security chain against real Postgres
 * (QA cases I1–I2): a unique-key filter answers in <em>one</em> request with an exact {@code count}
 * — matched → 1, unmatched → an empty page (never a full-list fallthrough) — while the absent-filter
 * path stays the unchanged paged {@code findAll}. The store is seeded past one page so the killed
 * client-side page-walk would demonstrably have had to walk.
 */
class DirectoryQueryFilterIT extends AbstractSecuredPostgresIT {

    private static final String CALLER = "it-directory-filter-subject";

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;

    private List<User> seedThreeUsers() {
        String run = UUID.randomUUID().toString();
        return List.of(
                users.save(new User(UUID.randomUUID(), "sub-a-" + run, "Ann")),
                users.save(new User(UUID.randomUUID(), "sub-b-" + run, "Ben")),
                users.save(new User(UUID.randomUUID(), "sub-c-" + run, "Cid")));
    }

    // I1 — ?subject=<known> answers the single row in ONE request, count exact, even though the
    // store spans multiple pages at perPage=2 (the walk the filter kills would have truncated).
    @Test
    void subjectFilterAnswersInOneRequestWithExactCount() {
        List<User> seeded = seedThreeUsers();
        User wanted = seeded.get(2); // last-seeded: past page 0 in createdAt order at perPage=2

        var response = rest.exchange(
                "/api/v1/users?subject={s}&perPage=2",
                HttpMethod.GET,
                AbacTestConfig.as(CALLER),
                UserPage.class,
                wanted.getSubject());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserPage page = response.getBody();
        assertThat(page).isNotNull();
        assertThat(page.getCount()).isEqualTo(1);
        assertThat(page.getItems()).singleElement().satisfies(u -> {
            assertThat(u.getId()).isEqualTo(wanted.getId());
            assertThat(u.getSubject()).isEqualTo(wanted.getSubject());
        });
    }

    // I1 — an unmatched subject is an EMPTY page (count 0): never 404, never the full list.
    @Test
    void unmatchedSubjectIsEmptyPage() {
        seedThreeUsers();

        var response = rest.exchange(
                "/api/v1/users?subject={s}",
                HttpMethod.GET,
                AbacTestConfig.as(CALLER),
                UserPage.class,
                "sub-nobody-" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserPage page = response.getBody();
        assertThat(page).isNotNull();
        assertThat(page.getCount()).isZero();
        assertThat(page.getItems()).isEmpty();
    }

    // I1 — no ?subject: the paged findAll is unchanged (envelope, defaults, all seeded rows present;
    // the shared container accumulates rows across ITs, so count is a lower bound).
    @Test
    void absentSubjectKeepsThePagedListUnchanged() {
        List<User> seeded = seedThreeUsers();

        var full = rest.exchange(
                "/api/v1/users?perPage=100",
                HttpMethod.GET,
                AbacTestConfig.as(CALLER),
                UserPage.class);

        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserPage page = full.getBody();
        assertThat(page).isNotNull();
        assertThat(page.getCount()).isGreaterThanOrEqualTo(3);
        assertThat(page.getPage()).isZero();
        assertThat(page.getPerPage()).isEqualTo(100);
        for (User user : seeded) {
            assertThat(page.getItems()).anyMatch(u -> u.getId().equals(user.getId()));
        }

        // Paging itself still slices: a 2-wide window holds exactly 2 rows.
        var window = rest.exchange(
                "/api/v1/users?perPage=2",
                HttpMethod.GET,
                AbacTestConfig.as(CALLER),
                UserPage.class);
        assertThat(window.getBody()).isNotNull();
        assertThat(window.getBody().getItems()).hasSize(2);
    }

    // I2 — the (?targetType, ?targetId) composite lookup: matched pair → exactly the governing team;
    // unmatched pair → empty page; half-specified → 400 problem+json; no params → full list unchanged.
    @Test
    void targetPairFilterAnswersTheGoverningTeam() {
        Team wanted = teams.save(
                new Team(UUID.randomUUID(), "Wanted", "catalog", UUID.randomUUID()));
        teams.save(new Team(UUID.randomUUID(), "Decoy", "catalog", UUID.randomUUID()));

        var response = rest.exchange(
                "/api/v1/teams?targetType=catalog&targetId={id}",
                HttpMethod.GET,
                AbacTestConfig.as(CALLER),
                TeamPage.class,
                wanted.getTargetId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TeamPage page = response.getBody();
        assertThat(page).isNotNull();
        assertThat(page.getCount()).isEqualTo(1);
        assertThat(page.getItems()).singleElement().satisfies(t -> {
            assertThat(t.getId()).isEqualTo(wanted.getId());
            assertThat(t.getTargetId()).isEqualTo(wanted.getTargetId());
        });
    }

    @Test
    void unmatchedTargetPairIsEmptyPage() {
        teams.save(new Team(UUID.randomUUID(), "Present", "catalog", UUID.randomUUID()));

        var response = rest.exchange(
                "/api/v1/teams?targetType=catalog&targetId={id}",
                HttpMethod.GET,
                AbacTestConfig.as(CALLER),
                TeamPage.class,
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TeamPage page = response.getBody();
        assertThat(page).isNotNull();
        assertThat(page.getCount()).isZero();
        assertThat(page.getItems()).isEmpty();
    }

    // I2 — the both-or-400 guard: a half-specified pair is a typed validation error, never the list.
    @Test
    void halfSpecifiedTargetPairIs400ProblemJson() {
        var response = rest.exchange(
                "/api/v1/teams?targetType=catalog",
                HttpMethod.GET,
                AbacTestConfig.as(CALLER),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/problem+json");
        assertThat(response.getBody()).contains("\"errorCode\":\"VALIDATION_FAILED\"");
    }

    @Test
    void absentTargetPairKeepsThePagedTeamListUnchanged() {
        Team one = teams.save(new Team(UUID.randomUUID(), "One", "catalog", UUID.randomUUID()));
        Team two = teams.save(new Team(UUID.randomUUID(), "Two", "catalog", UUID.randomUUID()));

        var response = rest.exchange(
                "/api/v1/teams?perPage=100",
                HttpMethod.GET,
                AbacTestConfig.as(CALLER),
                TeamPage.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TeamPage page = response.getBody();
        assertThat(page).isNotNull();
        assertThat(page.getCount()).isGreaterThanOrEqualTo(2);
        assertThat(page.getPage()).isZero();
        assertThat(page.getPerPage()).isEqualTo(100);
        assertThat(page.getItems()).anyMatch(t -> t.getId().equals(one.getId()));
        assertThat(page.getItems()).anyMatch(t -> t.getId().equals(two.getId()));
    }
}
