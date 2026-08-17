package dev.dmitriikonovalov.example.catalog.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stamps the {@code _provenance} affordance onto the generated {@code Catalog} DTO (via the OpenAPI
 * {@code x-implements} extension) — <em>by which access path is this row in front of you?</em>
 * {@code "member"} (a membership grant; membership always wins when both apply) or
 * {@code "supervised"} (the disjoint supervised read path, ADR 0029). Written by
 * {@code CatalogProvenanceAdvice} after the handler returns; never accepted on input.
 *
 * <p>This is <strong>affordance, not enforcement</strong> (ADR 0033, taking ADR 0016's stance
 * verbatim): a client uses it to explain and to <em>predict</em> — "this production catalog will ask
 * me for a second factor" — and every decision remains the server's. Predicted-and-wrong is a UI
 * bug, never a security event.
 *
 * <p><strong>Absent when not computed — never {@code null} on the wire, never a default.</strong>
 * An absent {@code _provenance} means <em>unknown</em>, not {@code "member"}; collapsing those two
 * is exactly the honesty failure {@link dev.dmitriikonovalov.opaabac.security.web.Enrichable}
 * established the omit-never-fabricate contract to prevent. Note the asymmetry with {@code _actions}:
 * that field needs {@code NON_EMPTY} because the generated DTO initializes its backing map to an
 * empty one; a scalar's field starts {@code null}, so {@code NON_NULL} is what buys absence here.
 * There is no global Jackson inclusion setting and the generated DTO carries no inclusion annotation
 * of its own, so this annotation is load-bearing.
 *
 * <p>Deliberately a <em>second</em> marker rather than a member of {@code Enrichable}: that is a
 * library type, and an access-path label is a domain noun this example owns. No library module
 * changes for this affordance.
 */
public interface CatalogProvenanceCarrier {

    /** The membership access path — a real grant on the catalog, or on a team that governs it. */
    String MEMBER = "member";

    /** The supervised access path — {@code S \ M}, derived from the reporting relation (ADR 0029). */
    String SUPERVISED = "supervised";

    /**
     * The access-path label; {@code null} until stamped, and on every degrade branch.
     *
     * <p>{@code @JsonInclude(NON_NULL)} is load-bearing for the absent-when-not-computed contract —
     * see the class Javadoc.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("_provenance")
    String getProvenance();

    /** Set by the advice only. */
    void setProvenance(String provenance);
}
