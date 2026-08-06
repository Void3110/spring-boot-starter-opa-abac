package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.EffectiveRoleService;
import dev.dmitriikonovalov.example.usermgmt.service.RoleDefinitionService;
import dev.dmitriikonovalov.example.usermgmt.service.SupervisorRoles;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;

/**
 * T3 — <b>I7, the seam test</b> (plus <b>U41</b>, the forgery case).
 *
 * <p><b>Why this test is required, not optional.</b> The {@code opa test} cases for ADR 0031 are
 * hand-written: they would stay green if the Java side silently stopped stamping
 * {@code attributes.provenance = "membership"} — and every member would lose child access in
 * production. Nothing else in the suite pins the Java half of the invariant.
 */
class ProvenanceStampIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;
    @Autowired private RoleDefinitionRepository roles;
    @Autowired private EffectiveRoleService effectiveRoles;
    @Autowired private RoleDefinitionService roleDefinitions;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team teamFor(UUID targetId) {
        return teams.save(new Team(UUID.randomUUID(), "T-" + targetId, "catalog", targetId));
    }

    private TeamMembership seat(Team team, User user, UUID roleId) {
        return memberships.save(
                new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), roleId));
    }

    @Test // I7 — THE SEAM TEST: resourceRole stamps membership provenance on a real membership
    void resourceRoleStampsMembershipProvenance() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("i7-member");
        TeamMembership m = seat(team, member, SystemRoles.OWNER_ID);

        RoleDefinition role = effectiveRoles.resourceRole(m, "catalog");

        assertThat(role.attributes())
                .containsEntry(
                        SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_MEMBERSHIP);
        // The stamp is ADDITIVE — the role's own attributes survive alongside it.
        assertThat(role.permissions()).containsKey("catalog");
    }

    @Test // I7 — and it rides the resolve WIRE, which is what the policies actually read
    void membershipProvenanceRidesTheResolveWire() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("i7-wire");
        seat(team, member, SystemRoles.MEMBER_ID);

        var res = rest.getForEntity(
                "/internal/effective-role?userId=" + member.getSubject()
                        + "&resourceType=catalog&resourceId=" + target,
                RoleDefinition.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().attributes())
                .containsEntry(
                        SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_MEMBERSHIP);
    }

    @Test // the stamp OVERWRITES rather than merges — a stored value never survives onto the wire
    void storedProvenanceIsOverwrittenOnTheReadPath() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("i7-overwrite");
        // Written straight to the repository, bypassing the service's write-path strip — this is the
        // "any future path that returns a stored role" case ADR 0031 §3 guards against.
        RoleDefinitionEntity forged = roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(),
                "forged-role",
                false,
                team.getId(),
                Map.of("role_level", 20, SupervisorRoles.PROVENANCE_ATTRIBUTE, "supervised"),
                Map.of("catalog", List.of("READ"))));
        TeamMembership m = seat(team, member, forged.getId());

        RoleDefinition role = effectiveRoles.resourceRole(m, "catalog");

        assertThat(role.attributes())
                .containsEntry(
                        SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_MEMBERSHIP);
        assertThat(role.attributes()).containsEntry("role_level", 20); // other attributes untouched
    }

    @Test // U41 — a CLIENT-supplied provenance is stripped on the WRITE path: it never reaches storage
    void clientSuppliedProvenanceIsStrippedOnWrite() {
        Team team = teamFor(UUID.randomUUID());

        RoleDefinitionEntity created = roleDefinitions.create(
                team.getId(),
                "client-authored",
                20,
                Map.of(SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_MEMBERSHIP,
                        "colour", "blue"),
                Map.of("catalog", List.of("READ")),
                Map.of(),
                Map.of(),
                null);

        assertThat(created.getAttributes())
                .doesNotContainKey(SupervisorRoles.PROVENANCE_ATTRIBUTE)
                .containsEntry("colour", "blue") // non-reserved attributes are untouched
                .containsEntry("role_level", 20);

        // …and the same on update.
        RoleDefinitionEntity updated = roleDefinitions.update(
                team.getId(),
                "client-authored",
                20,
                Map.of(SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_MEMBERSHIP),
                Map.of("catalog", List.of("READ")),
                Map.of(),
                Map.of(),
                null);
        assertThat(updated.getAttributes())
                .doesNotContainKey(SupervisorRoles.PROVENANCE_ATTRIBUTE);
    }

    @Test // the synthesized supervisor role carries the OTHER value — the two are never confusable
    void supervisorRoleCarriesSupervisedProvenanceNotMembership() {
        assertThat(SupervisorRoles.readOnlyFor("catalog").attributes())
                .containsEntry(
                        SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_SUPERVISED);
    }
}
