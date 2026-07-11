package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
            entry.getValue().forEach(a -> actions.add(a.asString()));
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

    @Test // Phase 6.7 — the authorable + control-plane sets partition the full category vocabulary
    void authorableAndControlPlaneSetsPartitionAllCategories() {
        // every category is in exactly one of the two sets (no overlap, full cover)
        assertThat(PermissionCategories.AUTHORABLE_CATEGORIES)
                .doesNotContainAnyElementsOf(PermissionCategories.CONTROL_PLANE_CATEGORIES);
        var union = new java.util.HashSet<String>(PermissionCategories.AUTHORABLE_CATEGORIES);
        union.addAll(PermissionCategories.CONTROL_PLANE_CATEGORIES);
        assertThat(union).isEqualTo(PermissionCategories.categories());
        // CONTROL is control-plane, never authorable
        assertThat(PermissionCategories.CONTROL_PLANE_CATEGORIES).containsExactly("CONTROL");
        // no authoring ceiling references a control-plane category
        for (int level : PermissionCategories.AUTHORABLE_LEVELS) {
            assertThat(PermissionCategories.ceiling(level))
                    .as("ceiling(%d) holds no control-plane category", level)
                    .doesNotContainAnyElementsOf(PermissionCategories.CONTROL_PLANE_CATEGORIES);
        }
    }

    @Test // Phase 6.7 (ADR 0015) — team.rego is category-driven, so the user-service bundle carries
    // verbatim copies of permissions.rego + permission_categories.json (the shared expansion home).
    // These MUST stay byte-identical to the infra copies (the mirror obligation) — a local drift guard
    // mirroring the CI `diff` step, so drift breaks `./gradlew build` without needing Docker.
    void serviceBundlePolicyCopiesAreByteIdenticalToInfra() throws IOException {
        for (String f : new String[] {"permission_categories.json", "permissions.rego"}) {
            Path infra = repoFile(Path.of("infra", "opa", "policies", f));
            Path service = repoFile(Path.of("example-user-management-service",
                    "src", "main", "resources", "opa", "policies", f));
            assertThat(Files.readString(service))
                    .as("service-bundle %s must be byte-identical to the infra copy (mirror obligation)", f)
                    .isEqualTo(Files.readString(infra));
        }
    }

    /** The repo-relative data file, found by walking up from the module's working directory. */
    private static Path opaTable() {
        return repoFile(Path.of("infra", "opa", "policies", "permission_categories.json"));
    }

    /** Resolve a repo-relative path by walking up from the module's working directory. */
    private static Path repoFile(Path relative) {
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
