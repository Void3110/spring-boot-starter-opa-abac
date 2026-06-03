package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.example.usermgmt.domain.TagCardinality;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TagScope;
import dev.dmitriikonovalov.example.usermgmt.domain.TagValueType;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Ticket-1 domain ITs (D1–D5, D9) against real Postgres via Testcontainers: the schema + seed, the
 * allowed-values JSONB round-trip, the two partial-unique indexes, and the read API. {@code ddl-auto:
 * validate} (the app's profile) proves the JPA mapping matches the Liquibase schema by booting at all.
 */
class TagDefinitionDomainIT extends AbstractPostgresIT {

    @Autowired private TagDefinitionRepository tagDefinitions;
    @Autowired private TeamRepository teams;

    private static final UUID SENSITIVITY_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID REGION_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private Team team(String name) {
        return teams.save(new Team(UUID.randomUUID(), name, "catalog", UUID.randomUUID()));
    }

    private TagDefinition global(String key, TagValueType type, TagCardinality card, List<String> allowed) {
        return new TagDefinition(
                UUID.randomUUID(), key, TagScope.GLOBAL, null, type, card, allowed, null, false);
    }

    private TagDefinition teamKey(UUID teamId, String key) {
        return new TagDefinition(
                UUID.randomUUID(), key, TagScope.TEAM, teamId,
                TagValueType.ENUM, TagCardinality.MULTI, List.of("emea", "amer", "apac"), null, false);
    }

    // --- D2: the global system keys are seeded --------------------------------

    @Test
    void seedsGlobalSystemKeys() {
        TagDefinition sensitivity = tagDefinitions.findById(SENSITIVITY_ID).orElseThrow();
        assertThat(sensitivity.getKey()).isEqualTo("sensitivity");
        assertThat(sensitivity.getScope()).isEqualTo(TagScope.GLOBAL);
        assertThat(sensitivity.getTeamId()).isNull();
        assertThat(sensitivity.isSystem()).isTrue();
        assertThat(sensitivity.getValueType()).isEqualTo(TagValueType.ENUM);
        assertThat(sensitivity.getCardinality()).isEqualTo(TagCardinality.SINGLE);

        TagDefinition region = tagDefinitions.findById(REGION_ID).orElseThrow();
        assertThat(region.getCardinality()).isEqualTo(TagCardinality.MULTI);
        assertThat(region.isSystem()).isTrue();
    }

    // --- D3: allowedValues JSONB round-trips ----------------------------------

    @Test
    void allowedValuesRoundTripThroughJsonb() {
        TagDefinition sensitivity = tagDefinitions.findById(SENSITIVITY_ID).orElseThrow();
        assertThat(sensitivity.getAllowedValues())
                .containsExactly("public", "internal", "confidential");

        TagDefinition region = tagDefinitions.findById(REGION_ID).orElseThrow();
        assertThat(region.getAllowedValues()).containsExactly("emea", "amer", "apac");
    }

    // --- D4: partial-unique on global key -------------------------------------

    @Test
    void secondGlobalSensitivityViolatesPartialUnique() {
        assertThatThrownBy(() -> tagDefinitions.saveAndFlush(
                global("sensitivity", TagValueType.ENUM, TagCardinality.SINGLE, List.of("a", "b"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- D5: a key is allowed once globally AND once per team -----------------

    @Test
    void sameKeyAllowedGloballyAndPerTeam() {
        Team t = team("Acme");
        // A global 'region' is already seeded; a team 'region' is allowed (independent partial index).
        TagDefinition saved = tagDefinitions.saveAndFlush(teamKey(t.getId(), "region"));
        assertThat(saved.getId()).isNotNull();
        assertThat(tagDefinitions.findByTeamIdAndKey(t.getId(), "region")).isPresent();
    }

    @Test
    void twoTeamKeysSameTeamSameKeyViolatesPartialUnique() {
        Team t = team("Beta");
        tagDefinitions.saveAndFlush(teamKey(t.getId(), "tier"));
        assertThatThrownBy(() -> tagDefinitions.saveAndFlush(teamKey(t.getId(), "tier")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyAllowedAcrossDifferentTeams() {
        Team a = team("TeamA");
        Team b = team("TeamB");
        tagDefinitions.saveAndFlush(teamKey(a.getId(), "tier"));
        TagDefinition other = tagDefinitions.saveAndFlush(teamKey(b.getId(), "tier"));
        assertThat(other.getId()).isNotNull();
    }

}
