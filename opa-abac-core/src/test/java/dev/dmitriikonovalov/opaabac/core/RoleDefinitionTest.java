package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RoleDefinition} (U9) and {@link NoOpRoleDefinitionSupplier} (U10). */
class RoleDefinitionTest {

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
}
