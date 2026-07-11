package dev.dmitriikonovalov.example.catalog.security;

import dev.dmitriikonovalov.opaabac.security.web.Enrichable;
import java.util.List;

/**
 * Stamps the {@link Enrichable} affordance contract onto the generated {@code Product} DTO (via the
 * OpenAPI {@code x-implements} extension). Carries the type binding and the verb set — it <em>is</em>
 * the action registry and validation allowlist for {@code product}.
 *
 * <p>Verbs are instance-scoped and verified against the live {@code @OpaPreAuthorize} endpoints:
 * {@code view}/{@code update}/{@code delete}/{@code assign-tags} (the last dispatched from the PUT via
 * {@code TagDecisionGate}, like categories — products carry tags). {@code list}/{@code create} are
 * collection-level (excluded).
 */
public interface ProductEnrichable extends Enrichable {

    @Override
    default String abacResourceType() {
        return "product";
    }

    @Override
    default List<String> abacActions() {
        return List.of("view", "update", "delete", "assign-tags");
    }
}
