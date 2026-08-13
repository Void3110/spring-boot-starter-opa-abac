package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.TagCardinality;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TagScope;
import dev.dmitriikonovalov.example.usermgmt.domain.TagValueType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Production-tier T1 (U1, I1, I2): the {@code operatorManaged} dictionary flag, the {@code env} seed, and
 * the internal projection that carries the flag to the catalog service.
 *
 * <p>The flag is <b>additive</b>: the column defaults to {@code false}, so every pre-existing key — the
 * two seeded GLOBAL keys and every team key — keeps behaving exactly as before. Booting at all under the
 * app's {@code ddl-auto: validate} profile is what proves changeset {@code 0008} and the entity mapping
 * agree (I1's first half).
 */
class OperatorManagedTagIT extends AbstractSecuredPostgresIT {

    private static final UUID SENSITIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ENV_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    @Autowired private TagDefinitionRepository tagDefinitions;
    @Autowired private TestRestTemplate rest;

    // --- U1: the column maps, persists, and defaults false ---------------------

    @Test
    void operatorManagedDefaultsToFalseWhenUnset() {
        TagDefinition saved = tagDefinitions.saveAndFlush(new TagDefinition(
                UUID.randomUUID(),
                "unmanaged-" + UUID.randomUUID(),
                TagScope.GLOBAL,
                null,
                TagValueType.STRING,
                TagCardinality.SINGLE,
                List.of(),
                null,
                false));

        assertThat(tagDefinitions.findById(saved.getId()).orElseThrow().isOperatorManaged())
                .isFalse();
    }

    @Test
    void operatorManagedPersistsWhenSet() {
        TagDefinition saved = tagDefinitions.saveAndFlush(new TagDefinition(
                UUID.randomUUID(),
                "managed-" + UUID.randomUUID(),
                TagScope.GLOBAL,
                null,
                TagValueType.STRING,
                TagCardinality.SINGLE,
                List.of(),
                null,
                true,
                true));

        assertThat(tagDefinitions.findById(saved.getId()).orElseThrow().isOperatorManaged())
                .isTrue();
    }

    // --- I1: the env seed row --------------------------------------------------

    @Test
    void seedsEnvAsAnOperatorManagedGlobalEnum() {
        TagDefinition env = tagDefinitions.findById(ENV_ID).orElseThrow();

        assertThat(env.getKey()).isEqualTo("env");
        assertThat(env.getScope()).isEqualTo(TagScope.GLOBAL);
        assertThat(env.getTeamId()).isNull();
        assertThat(env.getValueType()).isEqualTo(TagValueType.ENUM);
        assertThat(env.getCardinality()).isEqualTo(TagCardinality.SINGLE);
        assertThat(env.getAllowedValues()).containsExactly("production", "staging", "dev");
        assertThat(env.isSystem()).isTrue();
        assertThat(env.isOperatorManaged()).isTrue();
    }

    @Test
    void preExistingSeedsStayUnmanaged() {
        assertThat(tagDefinitions.findById(SENSITIVITY_ID).orElseThrow().isOperatorManaged()).isFalse();
        assertThat(tagDefinitions.findById(REGION_ID).orElseThrow().isOperatorManaged()).isFalse();
    }

    // --- I2: the internal projection carries the flag --------------------------

    @Test
    void internalProjectionCarriesTheFlagForEnvAndFalseForTheOthers() {
        var response = rest.exchange(
                "/internal/tag-definitions?resourceType=catalog&resourceId={id}",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<
                        List<dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition>>() {},
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        assertThat(response.getBody())
                .filteredOn(d -> "env".equals(d.getKey()))
                .singleElement()
                .satisfies(d -> assertThat(d.getOperatorManaged()).isTrue());

        assertThat(response.getBody())
                .filteredOn(d -> "sensitivity".equals(d.getKey()) || "region".equals(d.getKey()))
                .hasSize(2)
                .allSatisfy(d -> assertThat(d.getOperatorManaged()).isFalse());
    }
}
