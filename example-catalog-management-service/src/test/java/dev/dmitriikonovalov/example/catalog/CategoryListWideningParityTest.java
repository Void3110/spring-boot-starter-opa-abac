package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parity pin for the list-widening token (review fix, 2026-06-12): {@code CategoryListAuthorizer}
 * asks the {@code SubtreeSpecResolver} for the {@code READ} category token because {@code list}
 * expands from {@code READ} — and from no other category. That equivalence lives in the OPA data
 * table ({@code infra/opa/policies/permission_categories.json}); if it ever moves, this test breaks
 * the build so the Java mirror is re-derived rather than silently drifting (the same posture as the
 * user-service's {@code PermissionCategoriesParityTest}).
 */
class CategoryListWideningParityTest {

    @Test
    void listExpandsFromReadAndOnlyRead() throws IOException {
        JsonNode table = new ObjectMapper()
                .readTree(Files.readString(opaTable()))
                .get("permission_categories");
        assertThat(table).as("permission_categories key present in the OPA data file").isNotNull();

        List<String> categoriesGrantingList = new ArrayList<>();
        table.properties().forEach(entry -> entry.getValue().forEach(action -> {
            if ("list".equals(action.asText())) {
                categoriesGrantingList.add(entry.getKey());
            }
        }));

        assertThat(categoriesGrantingList)
                .as("the categories whose expansion contains 'list' — CategoryListAuthorizer widens on READ")
                .containsExactly("READ");
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
