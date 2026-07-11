package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
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
import org.springframework.boot.resttestclient.TestRestTemplate;
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
    void ownerResolvesWithAllFourCategories() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var res = rest.getForEntity(url(owner, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo(SystemRoles.OWNER);
        // The "*" system-role permission is expanded to the concrete team-target type.
        assertThat(res.getBody().permissions())
                .containsEntry("catalog", List.of("READ", "WRITE", "TAG", "GRANT"));
    }

    @Test
    void readerResolvesReadOnly() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User reader = user("reader");
        grant(team, reader, SystemRoles.READER_ID);

        var res = rest.getForEntity(url(reader, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().permissions()).containsEntry("catalog", List.of("READ"));
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
                Map.of("catalog", List.of("READ", "WRITE"))));
        grant(team, member, custom.getId());

        var res = rest.getForEntity(url(member, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo("catalog-editor");
        assertThat(res.getBody().permissions()).containsEntry("catalog", List.of("READ", "WRITE"));
    }

    @Test // I3 — denied_actions ride the resolve wire (snake_case, wildcard-expanded like the grants)
    void resolveCarriesWildcardExpandedDenials() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("no-delete");
        RoleDefinitionEntity custom = new RoleDefinitionEntity(
                UUID.randomUUID(),
                "no-delete-editor",
                false,
                team.getId(),
                Map.of("role_level", 20),
                Map.of("*", List.of("READ", "WRITE")));
        custom.setDeniedActions(Map.of("*", List.of("delete")));
        roles.save(custom);
        grant(team, member, custom.getId());

        var res = rest.getForEntity(url(member, "catalog", target), RoleDefinition.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        // Both maps expand "*" to the concrete team-target type.
        assertThat(res.getBody().permissions()).containsEntry("catalog", List.of("READ", "WRITE"));
        assertThat(res.getBody().deniedActions()).containsEntry("catalog", List.of("delete"));

        // The wire shape is snake_case.
        var json = rest.getForEntity(url(member, "catalog", target), JsonNode.class).getBody();
        assertThat(json).isNotNull();
        assertThat(json.has("denied_actions")).isTrue();
        assertThat(json.get("denied_actions").get("catalog").get(0).asText()).isEqualTo("delete");
    }

    @Test // I3 — a denial-free role serializes WITHOUT the denied_actions field (NON_EMPTY)
    void resolveOmitsEmptyDenials() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User reader = user("plain-reader");
        grant(team, reader, SystemRoles.READER_ID);

        var json = rest.getForEntity(url(reader, "catalog", target), JsonNode.class).getBody();
        assertThat(json).isNotNull();
        assertThat(json.has("denied_actions")).isFalse();
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
                Map.of("catalog", List.of("READ")),
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
                Map.of("catalog", List.of("READ")),
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

    // --- Slice B4: GET /internal/governed-targets (I12 / U11) ------------------------------------

    private Team productTeamFor(UUID targetId) {
        return teams.save(new Team(UUID.randomUUID(), "ProdTeam", "product", targetId));
    }

    private String governedUrl(User u, String type) {
        return "/internal/governed-targets?subject=" + u.getSubject() + "&resourceType=" + type;
    }

    @Test // I12 — a single-team member governs exactly that team's catalog
    void governedTargetsReturnsTheMembersCatalog() {
        UUID target = UUID.randomUUID();
        Team team = teamFor(target);
        User member = user("gt-single");
        grant(team, member, SystemRoles.MEMBER_ID);

        var res = rest.getForEntity(governedUrl(member, "catalog"), UUID[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsExactly(target);
    }

    @Test // I12 / U11 — a multi-team member governs the UNION of distinct catalog ids
    void governedTargetsReturnsUnionForMultiTeamMember() {
        UUID targetX = UUID.randomUUID();
        UUID targetZ = UUID.randomUUID();
        Team teamX = teamFor(targetX);
        Team teamZ = teamFor(targetZ);
        User carol = user("gt-multi");
        grant(teamX, carol, SystemRoles.OWNER_ID);
        grant(teamZ, carol, SystemRoles.MEMBER_ID);

        var res = rest.getForEntity(governedUrl(carol, "catalog"), UUID[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsExactlyInAnyOrder(targetX, targetZ);
    }

    @Test // U11 — the resourceType filter: a membership on a product-team is NOT a governed catalog
    void governedTargetsFiltersByResourceType() {
        UUID catalogTarget = UUID.randomUUID();
        UUID productTarget = UUID.randomUUID();
        Team catalogTeam = teamFor(catalogTarget);
        Team productTeam = productTeamFor(productTarget);
        User member = user("gt-typed");
        grant(catalogTeam, member, SystemRoles.MEMBER_ID);
        grant(productTeam, member, SystemRoles.MEMBER_ID);

        var catalogs = rest.getForEntity(governedUrl(member, "catalog"), UUID[].class);
        assertThat(catalogs.getBody()).containsExactly(catalogTarget); // only the catalog team's target

        var products = rest.getForEntity(governedUrl(member, "product"), UUID[].class);
        assertThat(products.getBody()).containsExactly(productTarget); // type-scoped, exactly
    }

    @Test // I12 — unknown subject → [] (200, never 204/error) — the authoritative "governs nothing"
    void governedTargetsUnknownSubjectIsEmptyArray() {
        var res = rest.getForEntity(
                "/internal/governed-targets?subject=sub-nobody&resourceType=catalog", UUID[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    @Test // U11 — a known subject on no team of that type → [] (200)
    void governedTargetsKnownSubjectNoTeamIsEmptyArray() {
        User stranger = user("gt-stranger"); // a user with no memberships
        var res = rest.getForEntity(governedUrl(stranger, "catalog"), UUID[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    @Test // U11 — the DB enforces one-team-per-target (uq_team_target on (target_type,target_id)), so a
    // catalog can be governed by at most ONE team. governedTargets is therefore naturally distinct by id
    // for the realistic shape; this pins the schema invariant that makes the dedup a no-op in practice
    // (the LinkedHashSet in governedTargets is a defensive belt-and-braces, not a load-bearing dedup).
    void cannotCreateTwoTeamsGoverningTheSameCatalog() {
        UUID target = UUID.randomUUID();
        teams.save(new Team(UUID.randomUUID(), "TeamA", "catalog", target));
        assertThatThrownBy(() -> teams.saveAndFlush(new Team(UUID.randomUUID(), "TeamB", "catalog", target)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
