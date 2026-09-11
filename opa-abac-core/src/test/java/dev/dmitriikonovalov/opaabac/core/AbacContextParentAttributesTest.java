package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TAG-GATED-CREATE T2 (U13, U14, U15): {@code AbacContext.Resource.parentAttributes} is <b>additive</b>,
 * and its three states stay distinguishable on the wire (ADR 0034 — the {@code rootAttributes} contract
 * of ADR 0032, applied to the placement parent).
 *
 * <p><b>Additivity</b>: the 3-, 4- and 5-arg constructors still compile and serialize byte-for-byte as
 * before, so no existing consumer, policy or recorded fixture changes. <b>Distinguishability</b>:
 * {@code {}} must survive to the wire while {@code null} must not — {@code NON_NULL}, never
 * {@code NON_EMPTY}, and a null-preserving defensive copy.
 */
class AbacContextParentAttributesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String json(AbacContext.Resource resource) {
        return MAPPER.writeValueAsString(resource);
    }

    // --- U13: additivity — the prior arities are byte-identical ---------------

    @Test
    void theThreeArgConstructorSerializesAsBefore() {
        assertThat(json(new AbacContext.Resource("product", "p1", Map.of("tier", "gold"))))
                .isEqualTo("{\"type\":\"product\",\"id\":\"p1\",\"attributes\":{\"tier\":\"gold\"}}");
    }

    @Test
    void theFourArgConstructorSerializesAsBefore() {
        assertThat(json(new AbacContext.Resource(
                "category", "c1", Map.of(), List.of(new ParentRef("catalog", "cat1")))))
                .isEqualTo("{\"type\":\"category\",\"id\":\"c1\",\"attributes\":{},"
                        + "\"ancestors\":[{\"type\":\"catalog\",\"id\":\"cat1\"}]}");
    }

    @Test
    void theFiveArgConstructorSerializesAsBefore() {
        // The ADR 0032 shape: root-enriched, no placement parent — the field is absent, not empty.
        assertThat(json(new AbacContext.Resource(
                "category", "c1", Map.of(), List.of(), Map.of("env", "production"))))
                .isEqualTo("{\"type\":\"category\",\"id\":\"c1\",\"attributes\":{},"
                        + "\"root_attributes\":{\"env\":\"production\"}}")
                .doesNotContain("parent_attributes");
    }

    @Test
    void theCompatAritiesLeaveParentAttributesNull() {
        assertThat(new AbacContext.Resource("product", "p1", Map.of()).parentAttributes()).isNull();
        assertThat(new AbacContext.Resource("product", "p1", Map.of(), List.of()).parentAttributes())
                .isNull();
        assertThat(new AbacContext.Resource("product", "p1", Map.of(), List.of(), Map.of())
                .parentAttributes()).isNull();
    }

    // --- U14: the three states, on the wire -----------------------------------

    @Test
    void absentIsOmittedEntirely() {
        assertThat(json(new AbacContext.Resource("category", null, Map.of(), List.of(), null, null)))
                .doesNotContain("parent_attributes");
    }

    @Test
    void fetchedButUntaggedSerializesAsAnEmptyObject() {
        // NON_EMPTY would drop this and make "untagged parent" indistinguishable from "never found out".
        assertThat(json(new AbacContext.Resource("category", null, Map.of(), List.of(), null, Map.of())))
                .contains("\"parent_attributes\":{}");
    }

    @Test
    void taggedSerializesTheMap() {
        assertThat(json(new AbacContext.Resource(
                "product", null, Map.of(), List.of(), null, Map.of("region", List.of("emea")))))
                .contains("\"parent_attributes\":{\"region\":[\"emea\"]}");
    }

    @Test
    void bothEnrichmentsSerializeSideBySide_rootFirst() {
        // The top-level category create: the placement parent IS the governing root, so both carry the
        // catalog's map — by design, each from its own declaration; the policy reads only parent_attributes.
        Map<String, Object> catalogTags = Map.of("region", List.of("emea"));
        String serialized = json(new AbacContext.Resource(
                "category", null, Map.of("region", List.of("emea")), List.of(), catalogTags, catalogTags));

        assertThat(serialized)
                .isEqualTo("{\"type\":\"category\",\"id\":null,\"attributes\":{\"region\":[\"emea\"]},"
                        + "\"root_attributes\":{\"region\":[\"emea\"]},"
                        + "\"parent_attributes\":{\"region\":[\"emea\"]}}");
    }

    // --- U15: the compact constructor ------------------------------------------

    @Test
    void nullIsPreservedNeverCoercedToEmpty() {
        assertThat(new AbacContext.Resource("category", null, Map.of(), List.of(), null, null)
                .parentAttributes()).isNull();
        assertThat(new AbacContext.Resource("category", null, Map.of(), List.of(), null, Map.of())
                .parentAttributes()).isNotNull().isEmpty();
    }

    @Test
    void theDefensiveCopyIsUnmodifiableAndDetached() {
        Map<String, Object> source = new HashMap<>();
        source.put("region", "emea");
        AbacContext.Resource resource =
                new AbacContext.Resource("category", null, Map.of(), List.of(), null, source);

        source.put("region", "apac"); // a caller mutating what it handed us must not change the input
        assertThat(resource.parentAttributes()).containsEntry("region", "emea");
        assertThat(resource.parentAttributes().getClass().getName()).contains("Immutable");
    }

    @Test
    void theWholeContextCarriesTheFieldUnderResource() {
        AbacContext context = new AbacContext(
                new AbacContext.Subject("u1", List.of(), Map.of()),
                "category:create",
                new AbacContext.Resource("category", null, Map.of(), List.of(), null, Map.of("env", "dev")),
                Map.of());

        assertThat(json(context.resource())).contains("\"parent_attributes\":{\"env\":\"dev\"}");
        assertThat(MAPPER.writeValueAsString(context)).contains("\"parent_attributes\"");
    }
}
