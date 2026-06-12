package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * U9 — the app-side validation table is <b>parity-pinned</b> to the OPA data table
 * ({@code infra/opa/policies/permission_categories.json}): the runtime decision home is OPA, the Java
 * constant exists for 422-time validation only, and any drift between them breaks the build here.
 */
class PermissionCategoriesParityTest {

    @Test
    void javaTableMatchesOpaDataTable() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(opaTable()));
        JsonNode table = root.get("permission_categories");
        assertThat(table).as("permission_categories key present in the OPA data file").isNotNull();

        Map<String, List<String>> fromJson = new HashMap<>();
        table.properties().forEach(entry -> {
            List<String> actions = new ArrayList<>();
            entry.getValue().forEach(a -> actions.add(a.asText()));
            fromJson.put(entry.getKey(), actions);
        });

        assertThat(PermissionCategories.EXPANSION).isEqualTo(fromJson);
    }

    @Test
    void ceilingsOnlyReferenceKnownCategories() {
        for (int level : PermissionCategories.AUTHORABLE_LEVELS) {
            assertThat(PermissionCategories.categories())
                    .as("ceiling(%d) ⊆ the four categories", level)
                    .containsAll(PermissionCategories.ceiling(level));
        }
        // The ceilings themselves (the ladder's shape): GRANT only at 30.
        assertThat(PermissionCategories.ceiling(10)).containsExactlyInAnyOrder("READ");
        assertThat(PermissionCategories.ceiling(20)).containsExactlyInAnyOrder("READ", "WRITE", "TAG");
        assertThat(PermissionCategories.ceiling(25)).containsExactlyInAnyOrder("READ", "WRITE", "TAG");
        assertThat(PermissionCategories.ceiling(30))
                .containsExactlyInAnyOrder("READ", "WRITE", "TAG", "GRANT");
        assertThat(PermissionCategories.ceiling(40)).isEmpty(); // owner is never authorable
    }

    /** The repo-relative data file, found by walking up from the module's working directory. */
    private static Path opaTable() {
        Path relative = Path.of("infra", "opa", "policies", "permission_categories.json");
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate " + relative + " above " + Path.of("").toAbsolutePath());
    }
}
