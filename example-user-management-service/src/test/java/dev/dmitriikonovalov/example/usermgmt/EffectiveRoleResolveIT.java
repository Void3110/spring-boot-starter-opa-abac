package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

/**
 * Ticket-7 effective-role resolve ITs (E1–E7) against the internal API. The resolve walks live
 * membership, so a removed member resolves empty (revocation), and the returned JSON matches the
 * {@code core.RoleDefinition} wire shape exactly. Internal API → no auth needed.
 */
class EffectiveRoleResolveIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;
    @Autowired private RoleDefinitionRepository roles;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team teamFor(UUID targetId) {
        return teams.save(new Team(UUID.randomUUID(), "Acme", "catalog", targetId));
    }

    private TeamMembership grant(Team team, User user, UUID roleId) {
        return memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), roleId));
    }

    private String url(User u, String type, UUID id) {
        return "/internal/effective-role?userId=" + u.getSubject()
                + "&resourceType=" + type + "&resourceId=" + id;
    }

    @Test
    void ownerResolvesWithReadWrite() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var res = rest.getForEntity(url(owner, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo(SystemRoles.OWNER);
        // The "*" system-role permission is expanded to the concrete team-target type.
        assertThat(res.getBody().permissions()).containsEntry("catalog", List.of("read", "write"));
    }

    @Test
    void viewerResolvesReadOnly() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User viewer = user("viewer");
        grant(team, viewer, SystemRoles.VIEWER_ID);

        var res = rest.getForEntity(url(viewer, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().permissions()).containsEntry("catalog", List.of("read"));
    }

    @Test
    void customEditorRoleResolvesWithItsPermissions() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("member");
        RoleDefinitionEntity custom = roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(),
                "catalog-editor",
                false,
                team.getId(),
                Map.of("role_level", 25),
                Map.of("catalog", List.of("read", "write"))));
        grant(team, member, custom.getId());

        var res = rest.getForEntity(url(member, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo("catalog-editor");
        assertThat(res.getBody().permissions()).containsEntry("catalog", List.of("read", "write"));
    }

    @Test // RD2 — a role's requiredTags + matchMode ride through the resolve into the core.RoleDefinition
    void resolvePassesThroughRequiredTags() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("regional");
        RoleDefinitionEntity custom = roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(),
                "regional-reader",
                false,
                team.getId(),
                Map.of(),
                Map.of("catalog", List.of("read")),
                Map.of("region", List.of("emea")),
                "ALL_OF"));
        grant(team, member, custom.getId());

        var res = rest.getForEntity(url(member, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().requiredTags()).containsEntry("region", List.of("emea"));
        assertThat(res.getBody().matchMode())
                .isEqualTo(dev.dmitriikonovalov.opaabac.core.TagMatchMode.ALL_OF);

        // And the wire shape now carries the snake_case fields.
        var json = rest.getForEntity(url(member, "catalog", target), JsonNode.class).getBody();
        assertThat(json).isNotNull();
        assertThat(json.has("required_tags")).isTrue();
        assertThat(json.get("match_mode").asText()).isEqualTo("ALL_OF");
    }

    @Test // RD3 — an UNKNOWN stored match_mode narrows to ALL_OF (fail-closed), never widens to ANY_OF
    void unknownStoredMatchModeNarrowsToAllOf() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("corrupted-mode");
        RoleDefinitionEntity custom = roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(),
                "corrupted-mode-reader",
                false,
                team.getId(),
                Map.of(),
                Map.of("catalog", List.of("read")),
                Map.of("region", List.of("emea")),
                "BOGUS")); // unknown but fits the varchar(10) column — a corrupted/future value
        grant(team, member, custom.getId());

        var res = rest.getForEntity(url(member, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        // null would let core.RoleDefinition default the present requirement to the WIDER ANY_OF.
        assertThat(res.getBody().matchMode())
                .isEqualTo(dev.dmitriikonovalov.opaabac.core.TagMatchMode.ALL_OF);
    }

    @Test
    void noMatchingTeamResolvesEmpty() {
        User stranger = user("stranger");
        var res = rest.getForEntity(
                url(stranger, "catalog", UUID.randomUUID()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removedMemberResolvesEmpty() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("member");
        TeamMembership m = grant(team, member, SystemRoles.MEMBER_ID);

        // Present first...
        assertThat(rest.getForEntity(url(member, "catalog", target), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // ...then revoke; the resolve re-derives → empty.
        memberships.delete(m);
        assertThat(rest.getForEntity(url(member, "catalog", target), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void exactMatcherDoesNotResolveADifferentResource() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        // A different resource id → no team-target matches → empty.
        var res = rest.getForEntity(url(owner, "catalog", UUID.randomUUID()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // A different resource type, same id → also no match.
        var typeMismatch = rest.getForEntity(url(owner, "product", target), String.class);
        assertThat(typeMismatch.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void wireShapeMatchesCoreRoleDefinition() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var res = rest.getForEntity(url(owner, "catalog", target), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = res.getBody();
        assertThat(body).isNotNull();
        // Exactly the core.RoleDefinition fields: code, attributes, permissions.
        assertThat(body.has("code")).isTrue();
        assertThat(body.has("attributes")).isTrue();
        assertThat(body.has("permissions")).isTrue();
        assertThat(body.get("permissions").has("catalog")).isTrue();
    }
}
