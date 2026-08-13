package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Production-tier T3 (U12): {@code AbacContext.Resource.rootAttributes} is <b>additive</b>, and its three
 * states stay distinguishable on the wire (ADR 0032).
 *
 * <p>The two halves are equally load-bearing. <b>Additivity</b>: the 3-arg and 4-arg constructors still
 * compile and serialize byte-for-byte as before, so no existing consumer, policy or recorded fixture
 * changes. <b>Distinguishability</b>: {@code {}} must survive to the wire while {@code null} must not —
 * the reason the annotation is {@code NON_NULL} and never {@code NON_EMPTY}, and the reason the compact
 * constructor's defensive copy is null-preserving for this one component.
 */
class AbacContextRootAttributesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String json(AbacContext.Resource resource) {
        return MAPPER.writeValueAsString(resource);
    }

    // --- additivity: the prior arities are byte-identical ----------------------

    @Test
    void theThreeArgConstructorSerializesAsBefore() {
        assertThat(json(new AbacContext.Resource("product", "p1", Map.of("tier", "gold"))))
                .isEqualTo("{\"type\":\"product\",\"id\":\"p1\",\"attributes\":{\"tier\":\"gold\"}}");
    }

    @Test
    void theFourArgConstructorSerializesAsBefore() {
        String serialized = json(new AbacContext.Resource(
                "category", "c1", Map.of(), List.of(new ParentRef("catalog", "cat1"))));

        assertThat(serialized)
                .isEqualTo("{\"type\":\"category\",\"id\":\"c1\",\"attributes\":{},"
                        + "\"ancestors\":[{\"type\":\"catalog\",\"id\":\"cat1\"}]}")
                .doesNotContain("root_attributes");
    }

    @Test
    void theCompatAritiesLeaveRootAttributesNull() {
        assertThat(new AbacContext.Resource("product", "p1", Map.of()).rootAttributes()).isNull();
        assertThat(new AbacContext.Resource("product", "p1", Map.of(), List.of()).rootAttributes())
                .isNull();
    }

    // --- the three states, on the wire ----------------------------------------

    @Test
    void absentIsOmittedEntirely() {
        assertThat(json(new AbacContext.Resource("category", "c1", Map.of(), List.of(), null)))
                .doesNotContain("root_attributes");
    }

    @Test
    void fetchedButUntaggedSerializesAsAnEmptyObject() {
        // NON_EMPTY would drop this and make "untagged" indistinguishable from "we never found out" —
        // the exact fail-open the three-state contract exists to prevent.
        assertThat(json(new AbacContext.Resource("category", "c1", Map.of(), List.of(), Map.of())))
                .contains("\"root_attributes\":{}");
    }

    @Test
    void taggedSerializesTheMap() {
        assertThat(json(new AbacContext.Resource(
                "category", "c1", Map.of(), List.of(), Map.of("env", "production"))))
                .contains("\"root_attributes\":{\"env\":\"production\"}");
    }

    @Test
    void theDefensiveCopyPreservesNullButStillCopiesAMap() {
        Map<String, Object> source = new java.util.HashMap<>();
        source.put("env", "staging");
        AbacContext.Resource resource =
                new AbacContext.Resource("category", "c1", Map.of(), List.of(), source);

        source.put("env", "production"); // a caller mutating what it handed us must not change the input
        assertThat(resource.rootAttributes()).containsEntry("env", "staging");
    }

    @Test
    void theWholeContextCarriesTheFieldUnderResource() {
        AbacContext context = new AbacContext(
                new AbacContext.Subject("u1", List.of(), Map.of()),
                "category:view",
                new AbacContext.Resource("category", "c1", Map.of(), List.of(), Map.of("env", "dev")),
                Map.of());

        assertThat(json(context.resource())).contains("\"root_attributes\":{\"env\":\"dev\"}");
        assertThat(MAPPER.writeValueAsString(context)).contains("\"root_attributes\"");
    }
}
