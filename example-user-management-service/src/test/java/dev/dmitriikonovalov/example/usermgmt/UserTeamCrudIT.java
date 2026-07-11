package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.User;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.UserRequest;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Ticket-2 web CRUD ITs (D5): create/list/get users and list/get teams through the controllers,
 * over a real Postgres. Runs on a random port; each request authenticates as a fixed subject via the
 * {@link AbacTestConfig#SUBJECT_HEADER} header (the plain read/create endpoints only require
 * authentication, not a team role).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserTeamCrudIT extends AbstractPostgresIT {

    private static final String SUBJECT = "it-crud-subject";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TeamRepository teams;

    @Test
    void createListGetUser() {
        var request = new UserRequest().subject("kc-sub-" + UUID.randomUUID()).displayName("Alice");

        var created = rest.exchange(
                "/api/v1/users", HttpMethod.POST, AbacTestConfig.as(SUBJECT, request), User.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        UUID id = created.getBody().getId();
        assertThat(id).isNotNull();
        assertThat(created.getBody().getDisplayName()).isEqualTo("Alice");

        var fetched = rest.exchange(
                "/api/v1/users/{id}", HttpMethod.GET, AbacTestConfig.as(SUBJECT), User.class, id);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getId()).isEqualTo(id);

        var list = rest.exchange(
                "/api/v1/users?perPage=100",
                HttpMethod.GET,
                AbacTestConfig.as(SUBJECT),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.UserPage.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getItems()).anyMatch(u -> u.getId().equals(id));
    }

    @Test
    void getUnknownUserIs404() {
        var fetched = rest.exchange(
                "/api/v1/users/{id}", HttpMethod.GET, AbacTestConfig.as(SUBJECT), String.class,
                UUID.randomUUID());
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listAndGetTeam() {
        var team = teams.save(
                new Team(UUID.randomUUID(), "Acme catalog team", "catalog", UUID.randomUUID()));

        var fetched = rest.exchange(
                "/api/v1/teams/{id}",
                HttpMethod.GET,
                AbacTestConfig.as(SUBJECT),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.Team.class,
                team.getId());
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getTargetType()).isEqualTo("catalog");

        var list = rest.exchange(
                "/api/v1/teams?perPage=100",
                HttpMethod.GET,
                AbacTestConfig.as(SUBJECT),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.TeamPage.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getItems()).anyMatch(t -> t.getId().equals(team.getId()));
    }
}
