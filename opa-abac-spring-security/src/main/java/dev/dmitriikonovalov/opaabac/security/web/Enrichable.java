package dev.dmitriikonovalov.opaabac.security.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Marker for a resource DTO that may carry an {@code _actions} <em>affordance map</em> — for the
 * resource it is returned on, which actions the caller may perform (e.g.
 * {@code {"view":true,"update":false,"delete":false}}). The map is <strong>affordance, not
 * enforcement</strong>: it answers <em>"what could I do here?"</em>, it never blocks a request. The
 * three enforcement layers still decide independently.
 *
 * <p><strong>Opt-in is implementing this interface.</strong> A generated DTO is stamped with a
 * per-type sub-interface via the OpenAPI {@code x-implements} extension; no per-endpoint annotation,
 * no hand-editing. The {@code ActionEnrichmentAdvice} (a {@code ResponseBodyAdvice}) recognizes
 * {@code Enrichable} returns — single, {@code Iterable<Enrichable>}, or a paged envelope whose items
 * are {@code Enrichable} — computes each resource's map from the resource's <em>resolved
 * attributes</em> (one batch OPA call per resource type), and writes it inline.
 *
 * <pre>{@code
 * # in the OpenAPI spec, on each enrichable resource schema:
 * Category:
 *   x-implements: [ dev.dmitriikonovalov.example.catalog.security.CategoryEnrichable ]
 *   properties:
 *     _actions: { type: object, additionalProperties: { type: boolean }, readOnly: true }
 * }</pre>
 *
 * <p>The per-type sub-interface (app-owned) adds the type binding <em>and</em> the verb set — it
 * <strong>is</strong> the action registry and the action-validation allowlist; there is no separate
 * SPI bean:
 *
 * <pre>{@code
 * public interface CategoryEnrichable extends Enrichable {
 *     default String abacResourceType() { return "category"; }
 *     default List<String> abacActions() { return List.of("view", "update", "delete", "assign-tags"); }
 * }
 * }</pre>
 *
 * <p><strong>The degrade contract:</strong> a present {@code _actions} map is always <em>complete</em>
 * (every {@link #abacActions()} verb keyed with a real {@code true}/{@code false} verdict, computed
 * fresh); an <em>absent</em> map means enrichment could not be computed (a cache miss, a batch error,
 * an ancestor/role failure). The advice <strong>omits</strong> the map on any failure — it never
 * fabricates an all-{@code false} map. {@code _actions} is server-emitted and {@code readOnly} (never
 * accepted on input).
 */
public interface Enrichable {

    /** The resource id — generated resource DTOs already expose this. */
    UUID getId();

    /** The affordance map the advice writes; {@code null}/absent until enriched (or on degrade). */
    Map<String, Boolean> getActions();

    /** Set by {@code ActionEnrichmentAdvice} after the handler returns; never read from input. */
    void setActions(Map<String, Boolean> actions);

    /**
     * The ABAC resource type this DTO maps to (e.g. {@code "category"}); supplied by the per-type
     * sub-interface as a {@code default}. The advice re-qualifies each verb to {@code "type:verb"}
     * when building the OPA context.
     */
    String abacResourceType();

    /**
     * The instance-scoped verbs to evaluate for this resource type, in a stable order; supplied by the
     * per-type sub-interface as a {@code default}. Only fully-OPA-decided verbs belong here, so a
     * {@code true} verdict means the caller can actually perform the action (affordance honesty).
     */
    List<String> abacActions();
}
