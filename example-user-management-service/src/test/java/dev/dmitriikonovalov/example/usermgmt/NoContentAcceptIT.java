package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

/**
 * I3 (DIRECTORY-QUERY-FILTERS) — the {@code produces} content-negotiation fix: each of the four
 * 204-only operations ({@code transferOwnership}, {@code deleteRoleDefinition},
 * {@code deleteTeamTagDefinition}, {@code removeMember}), called with a bare
 * {@code Accept: application/json}, answers <b>204 with an empty body — not 406</b> — and still
 * answers 204 with no {@code Accept} at all. Uses the JDK {@link HttpClient} because the case under
 * test <em>is</em> the request's exact header set ({@code TestRestTemplate} always volunteers an
 * {@code Accept}). Fresh fixtures per header variant — the ops are destructive.
 */
class NoContentAcceptIT extends AbstractSecuredPostgresIT {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;

    private final HttpClient http = HttpClient.newHttpClient();

    /** A team with an owner, two members (one to remove, one to receive ownership), a custom role, and a tag key. */
    private record Fixture(Team team, User owner, User removable, User successor, String roleCode, String tagKey) {
    }

    private Fixture fixture() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        Team team = teams.save(new Team(UUID.randomUUID(), "Accept-" + run, "catalog", UUID.randomUUID()));
        User owner = users.save(new User(UUID.randomUUID(), "sub-owner-" + run, "Owner"));
        User removable = users.save(new User(UUID.randomUUID(), "sub-removable-" + run, "Removable"));
        User successor = users.save(new User(UUID.randomUUID(), "sub-successor-" + run, "Successor"));
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), owner.getId(), SystemRoles.OWNER_ID));
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), removable.getId(), SystemRoles.MEMBER_ID));
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), successor.getId(), SystemRoles.MEMBER_ID));

        // A deletable custom role (via the internal bootstrap seam — permitAll, validation intact).
        String roleCode = "accept-role-" + run;
        var role = rest.postForEntity(
                "/internal/bootstrap/custom-roles",
                Map.of("teamId", team.getId(), "code", roleCode, "roleLevel", 20,
                        "permissions", Map.of("catalog", List.of("READ"))),
                Map.class);
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.OK);

        // A deletable team-scoped tag key (via the management API, as the owner).
        String tagKey = "accept-key-" + run;
        var tag = rest.postForEntity(
                "/api/v1/teams/" + team.getId() + "/tag-definitions",
                AbacTestConfig.as(owner.getSubject(),
                        Map.of("key", tagKey, "valueType", "STRING", "cardinality", "SINGLE")),
                String.class);
        assertThat(tag.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return new Fixture(team, owner, removable, successor, roleCode, tagKey);
    }

    /** Sends the request with an exact header set; {@code accept == null} means NO Accept header at all. */
    private HttpResponse<String> call(String method, String path, String body, String subject, String accept)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(AbacTestConfig.SUBJECT_HEADER, subject);
        if (accept != null) {
            request.header("Accept", accept);
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertAllFourOpsAnswer204(String accept) throws IOException, InterruptedException {
        Fixture fx = fixture();
        String base = "/api/v1/teams/" + fx.team().getId();
        String owner = fx.owner().getSubject();

        var deleteRole = call("DELETE", base + "/role-definitions/" + fx.roleCode(), null, owner, accept);
        assertThat(deleteRole.statusCode()).as("deleteRoleDefinition, Accept=%s", accept).isEqualTo(204);
        assertThat(deleteRole.body()).isEmpty();

        var deleteTag = call("DELETE", base + "/tag-definitions/" + fx.tagKey(), null, owner, accept);
        assertThat(deleteTag.statusCode()).as("deleteTeamTagDefinition, Accept=%s", accept).isEqualTo(204);
        assertThat(deleteTag.body()).isEmpty();

        var removeMember = call("DELETE", base + "/members/" + fx.removable().getId(), null, owner, accept);
        assertThat(removeMember.statusCode()).as("removeMember, Accept=%s", accept).isEqualTo(204);
        assertThat(removeMember.body()).isEmpty();

        var transfer = call("POST", base + "/transfer-ownership",
                "{\"newOwnerUserId\":\"" + fx.successor().getId() + "\"}", owner, accept);
        assertThat(transfer.statusCode()).as("transferOwnership, Accept=%s", accept).isEqualTo(204);
        assertThat(transfer.body()).isEmpty();
    }

    // I3 — the headline: a bare Accept: application/json is admitted (204), no longer 406'd.
    @Test
    void bareJsonAcceptAnswers204NotOn406OnAllFourOps() throws Exception {
        assertAllFourOpsAnswer204("application/json");
    }

    // I3 — the regression guard: no Accept header at all still answers 204 (as it always did).
    @Test
    void absentAcceptStillAnswers204OnAllFourOps() throws Exception {
        assertAllFourOpsAnswer204(null);
    }
}
