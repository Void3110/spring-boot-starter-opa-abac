package dev.dmitriikonovalov.example.catalog.security;

import dev.dmitriikonovalov.opaabac.security.web.Enrichable;
import java.util.List;

/**
 * Stamps the {@link Enrichable} affordance contract onto the generated {@code Category} DTO (via the
 * OpenAPI {@code x-implements} extension). Carries the type binding and the verb set — it <em>is</em>
 * the action registry and validation allowlist for {@code category}.
 *
 * <p>Verbs are instance-scoped and verified against the live {@code @OpaPreAuthorize} endpoints:
 * {@code view}/{@code update}/{@code delete}/{@code assign-tags} (a category carries tags, and
 * {@code category:assign-tags} is dispatched on the tags-delta {@code PUT} via {@code TagDecisionGate}).
 * {@code list}/{@code create} are collection-level (excluded).
 */
public interface CategoryEnrichable extends Enrichable {

    @Override
    default String abacResourceType() {
        return "category";
    }

    @Override
    default List<String> abacActions() {
        return List.of("view", "update", "delete", "assign-tags");
    }
}
