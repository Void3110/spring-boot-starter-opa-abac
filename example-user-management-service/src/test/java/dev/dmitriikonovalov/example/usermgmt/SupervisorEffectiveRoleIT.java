package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.ReportingEdge;
import dev.dmitriikonovalov.example.usermgmt.domain.ReportingEdgeRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.SupervisorRoles;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * T2 integration case <b>I3</b> — {@code /internal/effective-role} over HTTP against a real
 * repository, exercising all branches of the ordered fallthrough end to end (U11–U15), plus the
 * batch {@code /internal/effective-roles} path, which routes through the same service and must
 * therefore see the supervisor branch <em>consistently</em> rather than by special-casing.
 */
class SupervisorEffectiveRoleIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;
    @Autowired private RoleDefinitionRepository roles;
    @Autowired private ReportingEdgeRepository edges;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team teamFor(UUID targetId) {
        return teams.save(new Team(UUID.randomUUID(), "T-" + targetId, "catalog", targetId));
    }

    private void seat(Team team, User user, UUID roleId) {
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), roleId));
    }

    private void reports(User manager, User report) {
        edges.save(new ReportingEdge(UUID.randomUUID(), manager.getId(), report.getId()));
    }

    private String url(User u, UUID id) {
        return "/internal/effective-role?userId=" + u.getSubject()
                + "&resourceType=catalog&resourceId=" + id;
    }

    @Test // U11 — a member resolves the MEMBERSHIP role, byte-identical to today (no supervised path)
    void memberStillResolvesTheMembershipRole() {
        UUID target = UUID.randomUUID();
        User member = user("u11-member");
        seat(teamFor(target), member, SystemRoles.READER_ID);

        var res = rest.getForEntity(url(member, target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().code()).isEqualTo(SystemRoles.READER);
        assertThat(res.getBody().permissions()).containsEntry("catalog", List.of("READ"));
        // Untouched by this slice: the membership role carries no supervised provenance.
        assertThat(res.getBody().attributes())
                .doesNotContainEntry(
                        SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_SUPERVISED);
    }

    @Test // U12 — a NON-member who supervises the target resolves the synthesized supervisor role
    void supervisorOnNoTeamResolvesTheSynthesizedRole() {
        UUID target = UUID.randomUUID();
        User anna = user("u12-anna"); // a member of NO team
        User bob = user("u12-bob");
        reports(anna, bob);
        seat(teamFor(target), bob, SystemRoles.OWNER_ID);

        var res = rest.getForEntity(url(anna, target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().code()).isEqualTo(SupervisorRoles.SUPERVISOR_CODE);
        assertThat(res.getBody().permissions()).isEqualTo(Map.of("catalog", List.of("READ")));
        assertThat(res.getBody().permissions()).doesNotContainKeys("category", "product", "*");
        assertThat(res.getBody().requiredTags()).isEmpty();
        assertThat(res.getBody().attributes())
                .containsEntry(
                        SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_SUPERVISED);
    }

    @Test // U13 — neither member nor supervisor → 204, unchanged
    void strangerStillResolves204() {
        User stranger = user("u13-stranger");
        var res = rest.getForEntity(url(stranger, UUID.randomUUID()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test // U15 — both member AND supervisor of the target: MEMBERSHIP WINS
    void membershipWinsOverSupervision() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User anna = user("u15-anna");
        User bob = user("u15-bob");
        reports(anna, bob);
        seat(team, bob, SystemRoles.OWNER_ID); // anna supervises the target through bob...
        seat(team, anna, SystemRoles.MEMBER_ID); // ...and is also a member of it herself

        var res = rest.getForEntity(url(anna, target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().code()).isEqualTo(SystemRoles.MEMBER); // NOT the supervisor code
        assertThat(res.getBody().attributes())
                .doesNotContainEntry(
                        SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_SUPERVISED);
    }

    @Test // the realm marker is UX-only: a supervisor-eligible subject with ZERO reports resolves 204
    void supervisorWithZeroReportsResolvesNothing() {
        UUID target = UUID.randomUUID();
        User claimant = user("u13-claimant"); // no reports, no memberships — only "eligibility"
        teamFor(target);

        assertThat(rest.getForEntity(url(claimant, target), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test // reach is CONTROL-capable only: a report's READER seat does not synthesize a role
    void reportsNonControlSeatDoesNotResolveASupervisorRole() {
        UUID target = UUID.randomUUID();
        User anna = user("noctl-anna");
        User bob = user("noctl-bob");
        reports(anna, bob);
        seat(teamFor(target), bob, SystemRoles.READER_ID);

        assertThat(rest.getForEntity(url(anna, target), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test // liveness — removing the reporting edge withdraws the role on the very next request
    void removingTheReportingEdgeWithdrawsTheRole() {
        UUID target = UUID.randomUUID();
        User anna = user("live-anna");
        User bob = user("live-bob");
        ReportingEdge edge = edges.save(
                new ReportingEdge(UUID.randomUUID(), anna.getId(), bob.getId()));
        seat(teamFor(target), bob, SystemRoles.OWNER_ID);

        assertThat(rest.getForEntity(url(anna, target), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        edges.delete(edge);

        assertThat(rest.getForEntity(url(anna, target), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test // a custom role bearing the reserved code is NOT an escalation (ADR 0029 §7): reach comes
    // from the org-relation seam, so the spoofer resolves only their own (non-supervised) membership
    void customRoleBearingTheReservedCodeGrantsNoSupervisedReach() {
        UUID ownTarget = UUID.randomUUID();
        UUID otherTarget = UUID.randomUUID();
        Team ownTeam = teamFor(ownTarget);
        teamFor(otherTarget);
        User spoofer = user("spoofer");
        RoleDefinitionEntity custom = roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(),
                SupervisorRoles.SUPERVISOR_CODE, // the same code, on a stored custom role
                false,
                ownTeam.getId(),
                Map.of("role_level", 20),
                Map.of("catalog", List.of("READ"))));
        seat(ownTeam, spoofer, custom.getId());

        // On their own team the stored role resolves (as any custom role would) …
        assertThat(rest.getForEntity(url(spoofer, ownTarget), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // … and the code buys no reach whatsoever onto anything else.
        assertThat(rest.getForEntity(url(spoofer, otherTarget), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test // the BATCH path routes through the same service, so it sees the supervisor branch too —
    // one entry per target, with the authoritative explicit null for the ungoverned one (ADR 0024)
    void batchResolveSeesTheSupervisorBranchConsistently() {
        UUID supervised = UUID.randomUUID();
        UUID ungoverned = UUID.randomUUID();
        User anna = user("batch-anna");
        User bob = user("batch-bob");
        reports(anna, bob);
        seat(teamFor(supervised), bob, SystemRoles.OWNER_ID);

        var res = rest.getForEntity(
                "/internal/effective-roles?userId=" + anna.getSubject()
                        + "&target=catalog:" + supervised
                        + "&target=catalog:" + ungoverned,
                JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = res.getBody();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("role").get("code").asString())
                .isEqualTo(SupervisorRoles.SUPERVISOR_CODE);
        assertThat(body.get(0).get("role").get("attributes").get("provenance").asString())
                .isEqualTo(SupervisorRoles.PROVENANCE_SUPERVISED);
        assertThat(body.get(1).get("role").isNull()).isTrue(); // explicit null, never 204
    }
}
