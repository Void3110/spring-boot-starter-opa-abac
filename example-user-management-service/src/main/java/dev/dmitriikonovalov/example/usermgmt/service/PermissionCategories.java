package dev.dmitriikonovalov.example.usermgmt.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The app-side <b>validation</b> table for the coarse permission categories (ADR 0007, Phase 6.5):
 * the four category tokens, their fine-action expansions, and the per-level authoring ceilings.
 *
 * <p><b>The runtime decision home stays OPA {@code data}</b> ({@code infra/opa/policies/
 * permission_categories.json}) — this constant exists for 422-time authoring validation only, and the
 * parity unit test (U9) pins it to the JSON so drift breaks the build. Never decide access from this
 * class.
 */
public final class PermissionCategories {

    /** Category → fine actions, exactly mirroring {@code data.permission_categories} (U9-pinned). */
    public static final Map<String, List<String>> EXPANSION = Map.of(
            "READ", List.of("view", "list"),
            "WRITE", List.of("create", "update", "delete"),
            "TAG", List.of("define-tags", "assign-tags"),
            "GRANT", List.of("assign-roles"));

    /** The levels a custom role may be authored at (owner 40 is never authorable). */
    public static final Set<Integer> AUTHORABLE_LEVELS = Set.of(10, 20, 25, 30);

    private PermissionCategories() {
    }

    /** The four category tokens. */
    public static Set<String> categories() {
        return EXPANSION.keySet();
    }

    /**
     * The categories a role of the given level may grant ({@code GRANT} only at 30). An unauthorable
     * level has an <b>empty</b> ceiling — it can grant nothing (fail-closed; the level itself is
     * rejected separately).
     */
    public static Set<String> ceiling(int roleLevel) {
        return switch (roleLevel) {
            case 10 -> Set.of("READ");
            case 20, 25 -> Set.of("READ", "WRITE", "TAG");
            case 30 -> Set.of("READ", "WRITE", "TAG", "GRANT");
            default -> Set.of();
        };
    }

    /** The fine actions a set of category tokens expands to; an unknown token contributes nothing. */
    public static Set<String> expand(Collection<String> categories) {
        Set<String> actions = new LinkedHashSet<>();
        for (String category : categories) {
            actions.addAll(EXPANSION.getOrDefault(category, List.of()));
        }
        return actions;
    }
}
