package dev.dmitriikonovalov.example.catalog.security;

import dev.dmitriikonovalov.opaabac.security.web.Enrichable;
import java.util.List;

/**
 * Stamps the {@link Enrichable} affordance contract onto the generated {@code Catalog} DTO (via the
 * OpenAPI {@code x-implements} extension). Carries the type binding and the verb set — it <em>is</em>
 * the action registry and validation allowlist for {@code catalog}.
 *
 * <p>Verbs are instance-scoped and verified against the live {@code @OpaPreAuthorize} endpoints:
 * {@code view}/{@code update}/{@code delete}/{@code assign-tags} (the last since catalogs became
 * taggable — the update handler's delta dispatch, ADR 0022). {@code list}/{@code create} are
 * collection-level (excluded).
 */
public interface CatalogEnrichable extends Enrichable {

    @Override
    default String abacResourceType() {
        return "catalog";
    }

    @Override
    default List<String> abacActions() {
        return List.of("view", "update", "delete", "assign-tags");
    }
}
