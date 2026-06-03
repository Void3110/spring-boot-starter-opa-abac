package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RoleDefinition} (U9) and {@link NoOpRoleDefinitionSupplier} (U10). */
class RoleDefinitionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test // U9 — defensive copies of attributes and permissions
    void defensiveCopies_inputMutationDoesNotLeak() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("role_level", 10);
        List<String> productVerbs = new ArrayList<>(List.of("read"));
        Map<String, List<String>> perms = new HashMap<>();
        perms.put("product", productVerbs);

        RoleDefinition def = new RoleDefinition("catalog-viewer", attrs, perms);

        // mutate the originals after construction
        attrs.put("injected", true);
        productVerbs.add("write");
        perms.put("category", List.of("read"));

        assertThat(def.attributes()).containsOnlyKeys("role_level");
        assertThat(def.permissions()).containsOnlyKeys("product");
        assertThat(def.permissions().get("product")).containsExactly("read");
    }

    @Test // U9b — null inputs normalize to empty
    void nullInputs_normalizeToEmpty() {
        RoleDefinition def = new RoleDefinition("r", null, null);
        assertThat(def.attributes()).isEmpty();
        assertThat(def.permissions()).isEmpty();
    }

    @Test // U9c — returned collections are immutable
    void returnedCollectionsAreImmutable() {
        RoleDefinition def =
                new RoleDefinition("r", Map.of("a", 1), Map.of("product", List.of("read")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> def.attributes().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> def.permissions().get("product").add("write"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test // U10
    void noOpSupplier_alwaysEmpty() {
        RoleDefinitionSupplier supplier = new NoOpRoleDefinitionSupplier();
        Optional<RoleDefinition> result = supplier.lookup("user-1", "product", "p-1");
        assertThat(result).isEmpty();
        assertThat(supplier.lookup("user-1", "product", null)).isEmpty();
    }

    // --- tag-based grants (additive, ticket 4) --------------------------------

    @Test // C-core1 — a role with no required tags serializes exactly as before (fields absent)
    void backCompatSerialization_noTagFieldsWhenUnused() throws Exception {
        RoleDefinition def = new RoleDefinition(
                "catalog-viewer", Map.of("role_level", 10), Map.of("category", List.of("read")));
        String json = MAPPER.writeValueAsString(def);

        assertThat(json).doesNotContain("required_tags").doesNotContain("match_mode");
        // The exact prior wire shape — only code/attributes/permissions.
        assertThat(MAPPER.readTree(json).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("code", "attributes", "permissions");
    }

    @Test // C-core2 — the new fields round-trip and default match_mode is ANY_OF
    void newFieldsRoundTrip() throws Exception {
        RoleDefinition def = new RoleDefinition(
                "regional-reader",
                Map.of(),
                Map.of("category", List.of("read")),
                Map.of("sensitivity", List.of("public", "internal")),
                TagMatchMode.ALL_OF);

        String json = MAPPER.writeValueAsString(def);
        assertThat(json).contains("required_tags").contains("match_mode").contains("ALL_OF");

        RoleDefinition back = MAPPER.readValue(json, RoleDefinition.class);
        assertThat(back.requiredTags()).containsEntry("sensitivity", List.of("public", "internal"));
        assertThat(back.matchMode()).isEqualTo(TagMatchMode.ALL_OF);
    }

    @Test // C-core2b — required tags present but no mode → defaults to ANY_OF
    void requiredTagsWithoutModeDefaultsToAnyOf() {
        RoleDefinition def = new RoleDefinition(
                "r", Map.of(), Map.of(), Map.of("region", List.of("emea")), null);
        assertThat(def.matchMode()).isEqualTo(TagMatchMode.ANY_OF);
    }

    @Test // C-core3 — convenience constructor leaves the tag fields empty/null
    void convenienceConstructorHasNoTagRequirement() {
        RoleDefinition def = new RoleDefinition("r", Map.of(), Map.of("category", List.of("read")));
        assertThat(def.requiredTags()).isEmpty();
        assertThat(def.matchMode()).isNull();
    }

    @Test // required tags are defensively copied + immutable
    void requiredTagsDefensiveCopyAndImmutable() {
        Map<String, List<String>> required = new HashMap<>();
        required.put("region", new ArrayList<>(List.of("emea")));
        RoleDefinition def = new RoleDefinition("r", Map.of(), Map.of(), required, TagMatchMode.ANY_OF);

        required.put("injected", List.of("x"));
        required.get("region").add("apac");

        assertThat(def.requiredTags()).containsOnlyKeys("region");
        assertThat(def.requiredTags().get("region")).containsExactly("emea");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> def.requiredTags().put("x", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test // an empty required-tags map keeps match_mode null (back-compat)
    void emptyRequiredTagsKeepsModeNull() {
        RoleDefinition def =
                new RoleDefinition("r", Map.of(), Map.of(), Map.of(), TagMatchMode.ALL_OF);
        assertThat(def.matchMode()).isNull();
    }
}
