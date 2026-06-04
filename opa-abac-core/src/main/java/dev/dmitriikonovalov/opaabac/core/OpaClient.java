package dev.dmitriikonovalov.opaabac.core;

import java.util.List;

/**
 * Client for evaluating ABAC decisions against an OPA server.
 *
 * <p>Three decision shapes, all <strong>fail-closed</strong> on any transport/parse error:
 *
 * <ul>
 *   <li>{@link #allow(AbacContext)} — a single yes/no decision (the spine);</li>
 *   <li>{@link #compile(AbacContext)} — partial evaluation: the residual conditions a row must satisfy,
 *       for list filtering (the resource is declared unknown);</li>
 *   <li>{@link #allowAll(List)} — a batch decision: N contexts → N booleans in one round-trip.</li>
 * </ul>
 *
 * <p><strong>The two filtering methods are abstract, not {@code default}.</strong> A {@code default}
 * returning allow-all would let a custom {@code OpaClient} silently inherit a <em>fail-open</em> filter;
 * forcing every implementation to write {@code compile}/{@code allowAll} keeps the fail-closed posture a
 * deliberate choice. {@link HttpOpaClient} is the production implementation.
 */
public interface OpaClient {

    /**
     * Evaluate a single authorization decision.
     *
     * @param context the ABAC context (serialized as OPA {@code input})
     * @return {@code true} if the policy allows the action
     */
    boolean allow(AbacContext context);

    /**
     * Partially evaluate the policy's {@code filter} entrypoint for the given context with the
     * <em>resource declared unknown</em>, returning the residual conditions a row must satisfy.
     *
     * <p>Backed by OPA's Compile API ({@code POST /v1/compile}) with {@code unknowns: ["input.resource"]}.
     * The residual is returned as a neutral {@link PartialResult} (DNF) the data-filtering layer
     * translates to a query predicate.
     *
     * <p><strong>Fails closed:</strong> any non-200, transport error, timeout, malformed body, or an
     * expression the parser cannot map → {@link PartialResult#denyAll()} (an empty result set). A compile
     * failure must never <em>widen</em> visibility.
     *
     * @param context the ABAC context — subject/action/role_definition are the known half; the resource
     *                is the unknown and is omitted from the serialized {@code input}
     * @return the residual; never {@code null}
     */
    PartialResult compile(AbacContext context);

    /**
     * Evaluate N authorization decisions in a single round-trip, positionally:
     * {@code result.get(i)} is the decision for {@code contexts.get(i)}.
     *
     * <p>Backed by a per-type {@code bulk} policy rule fed a list input. A reusable batch primitive — it
     * finishes the data-filtering post-fetch allowlist and is the same call action enrichment consumes.
     *
     * <p><strong>Fails closed:</strong> any non-200, transport error, timeout, malformed body, or a
     * result whose length does not match the input → a list of {@code false} of the same length. An empty
     * input list returns an empty list with no HTTP call.
     *
     * @param contexts the contexts to decide; each carries its own resource
     * @return one boolean per input context, same order, same length; never {@code null}
     */
    List<Boolean> allowAll(List<AbacContext> contexts);
}
