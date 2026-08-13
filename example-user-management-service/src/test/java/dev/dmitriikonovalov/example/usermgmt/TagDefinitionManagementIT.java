package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinitionRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinitionUpdate;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Ticket-2 management ITs (G1–G6): owner/administrator define team-scoped keys; member/viewer are denied
 * (the dogfooded {@code team:define-tags}); global/system keys are immutable; malformed definitions are
 * rejected. The policy-unit P1 cases live in {@code team_test.rego}.
 */
class TagDefinitionManagementIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;
    @Autowired private TagDefinitionRepository tagDefinitions;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team team() {
        return teams.save(new Team(UUID.randomUUID(), "Acme", "catalog", UUID.randomUUID()));
    }

    private void grant(Team team, User user, UUID roleId) {
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), roleId));
    }

    private static TagDefinitionRequest enumReq(String key, List<String> allowed) {
        return new TagDefinitionRequest()
                .key(key)
                .valueType(TagDefinitionRequest.ValueTypeEnum.ENUM)
                .cardinality(TagDefinitionRequest.CardinalityEnum.MULTI)
                .allowedValues(allowed);
    }

    // --- G1: owner defines a team-scoped key ----------------------------------

    @Test
    void ownerDefinesTeamScopedKey() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), enumReq("tier", List.of("gold", "silver"))),
                TagDefinition.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().getScope()).isEqualTo(TagDefinition.ScopeEnum.TEAM);
        assertThat(create.getBody().getTeamId()).isEqualTo(team.getId());
        assertThat(create.getBody().getSystem()).isFalse();
        assertThat(tagDefinitions.findByTeamIdAndKey(team.getId(), "tier")).isPresent();
    }

    // --- G2: administrator defines a team-scoped key --------------------------

    @Test
    void administratorDefinesTeamScopedKey() {
        Team team = team();
        User admin = user("admin");
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(admin.getSubject(), enumReq("tier", List.of("gold"))),
                TagDefinition.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // --- G3: member / viewer are denied (403) ---------------------------------

    @Test
    void memberCannotDefine() {
        Team team = team();
        User member = user("member");
        grant(team, member, SystemRoles.MEMBER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(member.getSubject(), enumReq("tier", List.of("gold"))),
                String.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void viewerCannotDefine() {
        Team team = team();
        User viewer = user("viewer");
        grant(team, viewer, SystemRoles.READER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(viewer.getSubject(), enumReq("tier", List.of("gold"))),
                String.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- G3b: READING the team dictionary is not management — a plain member lists it (200) -------
    // The vocabulary-isn't-sensitive stance: the flat ?teamId listing already serves the identical
    // rows bearer-only, and an ASSIGNER needs the vocabulary to know what is legal to assign. The
    // define-tags gate stays on the mutations (the G2/G3 cells above).

    @Test
    void memberCanReadTheTeamDictionary() {
        Team team = team();
        User member = user("member");
        grant(team, member, SystemRoles.MEMBER_ID);

        var list = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.GET,
                AbacTestConfig.as(member.getSubject()),
                String.class,
                team.getId());
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- G4: editing a global/system key is immutable (409) -------------------

    @Test
    void editingAGlobalKeyIsImmutable() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        // 'sensitivity' is a seeded GLOBAL/system key; editing it via the team route → 409.
        var update = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions/{k}",
                HttpMethod.PUT,
                AbacTestConfig.as(owner.getSubject(), new TagDefinitionUpdate()
                        .valueType(TagDefinitionUpdate.ValueTypeEnum.ENUM)
                        .cardinality(TagDefinitionUpdate.CardinalityEnum.SINGLE)
                        .allowedValues(List.of("public"))),
                String.class,
                team.getId(),
                "sensitivity");
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var delete = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions/{k}",
                HttpMethod.DELETE,
                AbacTestConfig.as(owner.getSubject()),
                String.class,
                team.getId(),
                "sensitivity");
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- G5: owner edits / deletes a team-scoped key --------------------------

    @Test
    void ownerEditsAndDeletesTeamScopedKey() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), enumReq("tier", List.of("gold", "silver"))),
                TagDefinition.class,
                team.getId());

        var update = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions/{k}",
                HttpMethod.PUT,
                AbacTestConfig.as(owner.getSubject(), new TagDefinitionUpdate()
                        .valueType(TagDefinitionUpdate.ValueTypeEnum.ENUM)
                        .cardinality(TagDefinitionUpdate.CardinalityEnum.MULTI)
                        .allowedValues(List.of("gold", "silver", "bronze"))),
                TagDefinition.class,
                team.getId(),
                "tier");
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getBody()).isNotNull();
        assertThat(update.getBody().getAllowedValues()).contains("bronze");

        var delete = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions/{k}",
                HttpMethod.DELETE,
                AbacTestConfig.as(owner.getSubject()),
                Void.class,
                team.getId(),
                "tier");
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tagDefinitions.findByTeamIdAndKey(team.getId(), "tier")).isEmpty();
    }

    // --- G6: malformed definition → 422 ---------------------------------------

    @Test
    void enumWithEmptyAllowedValuesIsRejected() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), enumReq("tier", List.of())),
                String.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void badKeyFormatIsRejected() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var create = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), enumReq("Tier WithSpaces", List.of("gold"))),
                String.class,
                team.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // --- bonus: duplicate team key → 409 --------------------------------------

    @Test
    void duplicateTeamKeyIsConflict() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), enumReq("tier", List.of("gold"))),
                TagDefinition.class,
                team.getId());

        var dup = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), enumReq("tier", List.of("silver"))),
                String.class,
                team.getId());
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- shadowing an operator-managed global is closed; ordinary shadowing stays open ---

    @Test
    void teamKeyCannotShadowAnOperatorManagedGlobal() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var shadow = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), enumReq("env", List.of("anything"))),
                String.class,
                team.getId());
        assertThat(shadow.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tagDefinitions.findByTeamIdAndKey(team.getId(), "env")).isEmpty();
    }

    @Test
    void teamKeyMayStillShadowAnOrdinaryGlobal() {
        Team team = team();
        User owner = user("owner");
        grant(team, owner, SystemRoles.OWNER_ID);

        var shadow = rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(), enumReq("region", List.of("north", "south"))),
                TagDefinition.class,
                team.getId());
        assertThat(shadow.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(tagDefinitions.findByTeamIdAndKey(team.getId(), "region")).isPresent();
    }
}
