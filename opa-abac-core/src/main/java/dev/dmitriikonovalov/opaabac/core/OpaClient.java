package dev.dmitriikonovalov.opaabac.core;

import java.util.List;

/**
 * Client for evaluating ABAC decisions against an OPA server.
 *
 * <p>Three decision shapes, all <strong>fail-closed</strong> on any transport/parse error:
 *
 * <ul>
 *   <li>{@link #allow(AbacContext)} — a single yes/no decision (the spine);</li>
 *   <li>{@link #decide(AbacContext)} — the same single decision, keeping any structured
 *       {@link DenyReason} the policy attached (ADR 0030 §6); a {@code default} that degrades to
 *       {@code allow} with no reason;</li>
 *   <li>{@link #compile(AbacContext)} — partial evaluation: the residual conditions a row must satisfy,
 *       for list filtering (the resource is declared unknown);</li>
 *   <li>{@link #allowAll(List)} — a batch decision: N contexts → N booleans in one round-trip.</li>
 * </ul>
 *
 * <p><strong>Reasons are a single-decision concern.</strong> {@code compile} and {@code allowAll} stay
 * boolean on purpose: a residual is a row predicate and a batch is an affordance list, and neither is a
 * request a client could re-authenticate for.
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
     * Evaluate a single authorization decision, keeping any <strong>structured</strong> deny reason the
     * policy attached (ADR 0030 §6).
     *
     * <p><strong>Additive by default.</strong> This is a {@code default} method that delegates to
     * {@link #allow(AbacContext)} with a {@code null} reason, so every implementation written before it
     * existed compiles and behaves unchanged — the deliberate alternative to versioning the envelope.
     * An implementation that can see the reason on the wire (the HTTP client) overrides it.
     *
     * <p><strong>A decorator MUST override this.</strong> A decorator that wraps a delegate and only
     * overrides {@code allow} would inherit this default, which calls <em>its own</em> {@code allow} —
     * so the delegate's reason is silently swallowed and every step-up deny degrades to a plain deny.
     * Overriding it is not an optimisation; it is the difference between a challenge and a dead end.
     *
     * <p><strong>Fails closed, without inventing:</strong> every failure path returns
     * {@link OpaDecision#deny()} — deny with a {@code null} reason. A reason is a promise that
     * re-authenticating fixes the deny; during an outage that promise would be false and would send
     * users into a re-authentication loop that changes nothing.
     *
     * @param context the ABAC context (serialized as OPA {@code input})
     * @return the decision, never {@code null}; {@code denyReason} is {@code null} unless the policy
     *         emitted a well-formed one
     */
    default OpaDecision decide(AbacContext context) {
        return OpaDecision.of(allow(context));
    }

    /**
     * Partially evaluate the policy's {@code filter} entrypoint for the given context with the
     * <em>resource declared unknown</em>, returning the residual conditions a row must satisfy.
     *
     * <p>Backed by OPA's Compile API ({@code POST /v1/compile}) with {@code unknowns: ["input.resource"]}.
     * The residual is returned as a neutral {@link PartialResult} (DNF) the data-filtering layer
     * translates to a query predicate.
     *
     * <p><strong>Fails closed:</strong> a <em>failed call</em> (non-200, transport error, timeout,
     * malformed body) → {@link PartialResult#error()} — deny-all flagged {@code fromError}, so a caller
     * also suppresses any widening or fallback it would otherwise compose with the residual. A
     * <em>policy-derived</em> deny (unsatisfiable query, or an expression the parser cannot map) →
     * {@link PartialResult#denyAll()} / {@link PartialResult#unsupported()}. A compile failure must never
     * <em>widen</em> visibility.
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
     * <p><strong>One resource type per batch.</strong> The batch is evaluated against a single per-type
     * policy document (one list endpoint → one type), so every context must carry the same resource type.
     * An implementation must reject a mixed batch fail-closed (all {@code false}), never evaluate items
     * against another type's policy.
     *
     * @param contexts the contexts to decide; each carries its own resource, all of the same type
     * @return one boolean per input context, same order, same length; never {@code null}
     */
    List<Boolean> allowAll(List<AbacContext> contexts);
}
