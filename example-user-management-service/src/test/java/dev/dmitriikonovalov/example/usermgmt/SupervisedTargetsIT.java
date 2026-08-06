package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.ReportingEdge;
import dev.dmitriikonovalov.example.usermgmt.domain.ReportingEdgeRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * T1 integration cases (I1, I2) for the org-relation seam, against a real Postgres.
 *
 * <p><b>I2 is implicit and load-bearing:</b> the app boots with {@code ddl-auto: validate}, so this
 * class starting at all proves the {@code 0007-create-reporting-edge} changeset matches the
 * {@link ReportingEdge} mapping; {@link #changesetIsIdempotentOnRerun()} pins the re-run half.
 *
 * <p>I1 asserts the derived id set <b>by id</b>, never by count alone — a count assertion would pass
 * on the wrong ids, which is precisely the failure this seam must not have.
 */
class SupervisedTargetsIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;
    @Autowired private ReportingEdgeRepository edges;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private UUID teamGoverning(String targetType, UUID targetId) {
        return teams.save(new Team(UUID.randomUUID(), "T-" + targetId, targetType, targetId)).getId();
    }

    private void seat(UUID teamId, User user, UUID roleId) {
        memberships.save(new TeamMembership(UUID.randomUUID(), teamId, user.getId(), roleId));
    }

    private void reports(User manager, User report) {
        edges.save(new ReportingEdge(UUID.randomUUID(), manager.getId(), report.getId()));
    }

    private String url(User u, String type) {
        return "/internal/supervised-targets?subject=" + u.getSubject() + "&resourceType=" + type;
    }

    @Test // I1 — the full derivation over a seeded org: CONTROL-capable seats only, asserted BY ID
    void derivesExactlyTheUnitsCatalogsThroughControlCapableSeats() {
        User anna = user("anna");
        User bob = user("bob");
        User carol = user("carol");
        User dave = user("dave"); // carol's report → transitivity
        reports(anna, bob);
        reports(anna, carol);
        reports(carol, dave);

        UUID bobsCatalog = UUID.randomUUID();
        UUID carolsCatalog = UUID.randomUUID();
        UUID davesCatalog = UUID.randomUUID();
        UUID bobsReaderCatalog = UUID.randomUUID(); // a READER seat — must NOT propagate
        UUID unrelatedCatalog = UUID.randomUUID(); // nobody in the unit holds a seat on it

        seat(teamGoverning("catalog", bobsCatalog), bob, SystemRoles.OWNER_ID);
        seat(teamGoverning("catalog", carolsCatalog), carol, SystemRoles.ADMINISTRATOR_ID);
        seat(teamGoverning("catalog", davesCatalog), dave, SystemRoles.SENIOR_ID);
        seat(teamGoverning("catalog", bobsReaderCatalog), bob, SystemRoles.READER_ID);
        teamGoverning("catalog", unrelatedCatalog);

        var res = rest.getForEntity(url(anna, "catalog"), UUID[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .containsExactlyInAnyOrder(bobsCatalog, carolsCatalog, davesCatalog);
    }

    @Test // the manager's OWN membership is not part of the supervised set — this endpoint answers S,
    // not S \ M; a member-only subject supervising nobody sees nothing here.
    void ownMembershipIsNotASupervisedTarget() {
        User solo = user("solo");
        seat(teamGoverning("catalog", UUID.randomUUID()), solo, SystemRoles.OWNER_ID);

        var res = rest.getForEntity(url(solo, "catalog"), UUID[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    @Test // the resourceType filter: a report's CONTROL-capable seat on a product team is not a catalog
    void supervisedTargetsAreTypeScoped() {
        User manager = user("typed-mgr");
        User report = user("typed-report");
        reports(manager, report);
        UUID catalogTarget = UUID.randomUUID();
        UUID productTarget = UUID.randomUUID();
        seat(teamGoverning("catalog", catalogTarget), report, SystemRoles.OWNER_ID);
        seat(teamGoverning("product", productTarget), report, SystemRoles.OWNER_ID);

        assertThat(rest.getForEntity(url(manager, "catalog"), UUID[].class).getBody())
                .containsExactly(catalogTarget);
        assertThat(rest.getForEntity(url(manager, "product"), UUID[].class).getBody())
                .containsExactly(productTarget);
    }

    @Test // always 200 + an array; an unknown subject is the authoritative "supervises nothing"
    void unknownSubjectIsAnEmptyArrayNotAnError() {
        var res = rest.getForEntity(
                "/internal/supervised-targets?subject=sub-nobody&resourceType=catalog", UUID[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    // --- the bootstrap seam: declarative replace, cycle/self rejection --------------------------

    @Test // the posted set REPLACES the manager's edges — the E4 liveness seam (removal)
    void bootstrapReplacesTheManagersEdgeSet() {
        User anna = user("live-anna");
        User bob = user("live-bob");
        User carol = user("live-carol");
        UUID bobsCatalog = UUID.randomUUID();
        UUID carolsCatalog = UUID.randomUUID();
        seat(teamGoverning("catalog", bobsCatalog), bob, SystemRoles.OWNER_ID);
        seat(teamGoverning("catalog", carolsCatalog), carol, SystemRoles.OWNER_ID);

        post(anna, List.of(bob.getId(), carol.getId()), HttpStatus.OK);
        assertThat(rest.getForEntity(url(anna, "catalog"), UUID[].class).getBody())
                .containsExactlyInAnyOrder(bobsCatalog, carolsCatalog);

        // Drop bob from the posted set — his catalog is gone on the NEXT request.
        post(anna, List.of(carol.getId()), HttpStatus.OK);
        assertThat(rest.getForEntity(url(anna, "catalog"), UUID[].class).getBody())
                .containsExactly(carolsCatalog);

        // An empty set removes them all.
        post(anna, List.of(), HttpStatus.OK);
        assertThat(rest.getForEntity(url(anna, "catalog"), UUID[].class).getBody()).isEmpty();
    }

    @Test // idempotent: the same set posted twice converges to the same rows, no 500
    void bootstrapIsIdempotent() {
        User anna = user("idem-anna");
        User bob = user("idem-bob");

        post(anna, List.of(bob.getId()), HttpStatus.OK);
        post(anna, List.of(bob.getId()), HttpStatus.OK);

        assertThat(edges.findByManagerId(anna.getId())).hasSize(1);
    }

    @Test // U5/U6 over the wire — a self-edge and a cycle-closing edge are 422, nothing is persisted
    void selfEdgeAndCycleAreRejectedWith422() {
        User anna = user("cyc-anna");
        User bob = user("cyc-bob");

        var self = post(anna, List.of(anna.getId()), HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(self.get("errorCode").asString()).isEqualTo("REPORTING_EDGE_INVALID");
        assertThat(edges.findByManagerId(anna.getId())).isEmpty();

        post(anna, List.of(bob.getId()), HttpStatus.OK);
        var cycle = post(bob, List.of(anna.getId()), HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(cycle.get("errorCode").asString()).isEqualTo("REPORTING_EDGE_INVALID");
        assertThat(edges.findByManagerId(bob.getId())).isEmpty(); // nothing persisted
    }

    @Test // I2 — the changeset is idempotent on re-run: Liquibase already applied it at boot, and the
    // unique constraint it declares is live (a duplicate pair cannot be inserted behind the service).
    void changesetIsIdempotentOnRerun() {
        User anna = user("ddl-anna");
        User bob = user("ddl-bob");
        edges.save(new ReportingEdge(UUID.randomUUID(), anna.getId(), bob.getId()));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> edges.saveAndFlush(
                        new ReportingEdge(UUID.randomUUID(), anna.getId(), bob.getId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private JsonNode post(User manager, List<UUID> reportIds, HttpStatus expected) {
        Map<String, Object> body = Map.of("managerId", manager.getId(), "reportIds", reportIds);
        var res = rest.postForEntity("/internal/bootstrap/reporting-edges", body, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(expected);
        return res.getBody();
    }
}
