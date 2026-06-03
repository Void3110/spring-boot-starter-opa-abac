package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.TagCardinality;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TagScope;
import dev.dmitriikonovalov.example.usermgmt.domain.TagValueType;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Ticket-1 read-API IT (D9): {@code GET /api/v1/tag-definitions} lists globals (and a team's keys when
 * {@code teamId} is given), {@code GET …/{id}} returns one. Runs over the real secured chain
 * (RANDOM_PORT), authenticating with the test subject header — the read surface needs only an
 * authenticated caller, not a resolved role.
 */
class TagDefinitionReadApiIT extends AbstractSecuredPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private TagDefinitionRepository tagDefinitions;
    @Autowired private TeamRepository teams;

    private Team team(String name) {
        return teams.save(new Team(UUID.randomUUID(), name, "catalog", UUID.randomUUID()));
    }

    private TagDefinition teamKey(UUID teamId, String key) {
        return tagDefinitions.saveAndFlush(new TagDefinition(
                UUID.randomUUID(), key, TagScope.TEAM, teamId,
                TagValueType.ENUM, TagCardinality.MULTI, List.of("emea", "amer", "apac"), null, false));
    }

    @Test
    void listsGlobalsThenGlobalsPlusTeamKeys() {
        Team t = team("ReadTeam");
        teamKey(t.getId(), "tier-" + UUID.randomUUID());
        String subject = "sub-reader-" + UUID.randomUUID();

        var globals = rest.exchange(
                "/api/v1/tag-definitions",
                HttpMethod.GET,
                AbacTestConfig.as(subject),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition[].class);
        assertThat(globals.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(globals.getBody()).isNotNull();
        assertThat(globals.getBody()).anyMatch(d -> d.getKey().equals("sensitivity"));
        assertThat(globals.getBody()).noneMatch(d -> d.getKey().startsWith("tier-"));

        var withTeam = rest.exchange(
                "/api/v1/tag-definitions?teamId={t}",
                HttpMethod.GET,
                AbacTestConfig.as(subject),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition[].class,
                t.getId());
        assertThat(withTeam.getBody()).anyMatch(d -> d.getKey().startsWith("tier-"));
        assertThat(withTeam.getBody()).anyMatch(d -> d.getKey().equals("sensitivity"));
    }

    @Test
    void getsOneById() {
        Team t = team("OneTeam");
        TagDefinition saved = teamKey(t.getId(), "tier-" + UUID.randomUUID());
        String subject = "sub-reader-" + UUID.randomUUID();

        var one = rest.exchange(
                "/api/v1/tag-definitions/{id}",
                HttpMethod.GET,
                AbacTestConfig.as(subject),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition.class,
                saved.getId());
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody()).isNotNull();
        assertThat(one.getBody().getScope())
                .isEqualTo(dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition.ScopeEnum.TEAM);
    }

    @Test
    void unknownIdIs404() {
        String subject = "sub-reader-" + UUID.randomUUID();
        var missing = rest.exchange(
                "/api/v1/tag-definitions/{id}",
                HttpMethod.GET,
                AbacTestConfig.as(subject),
                String.class,
                UUID.randomUUID());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
