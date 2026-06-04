package dev.dmitriikonovalov.opaabac.core;

import java.util.List;

/**
 * The compiled residual of an OPA <em>partial evaluation</em> — a neutral, framework-agnostic shape the
 * data-filtering layer can translate to a query predicate without knowing anything about OPA's AST.
 *
 * <p>When the policy is partially evaluated with the <em>subject</em> known and the <em>resource</em>
 * declared unknown ({@code unknowns: ["input.resource"]}), OPA returns the conditions the row must
 * satisfy, in <strong>disjunctive normal form</strong> (OR-of-ANDs). This record captures that as one of
 * three outcomes:
 *
 * <ul>
 *   <li>{@link Decision#ALLOW_ALL} — the query holds for every row (no predicate; match all);</li>
 *   <li>{@link Decision#DENY_ALL} — the query can never hold (an always-false predicate; match none).
 *       <strong>This is the fail-closed value</strong> ({@link #denyAll()}): every transport/parse/
 *       unsupported-expression failure resolves here, never to {@code ALLOW_ALL};</li>
 *   <li>{@link Decision#CONDITIONAL} — the row must satisfy {@link #clauses()} as DNF:
 *       {@code (c0 AND c1) OR (c2) OR …}.</li>
 * </ul>
 *
 * <h2>The ALLOW_ALL vs DENY_ALL boundary (fail-closed)</h2>
 * The empty {@code result} the OPA Compile API returns when a query is <em>unsatisfiable</em> is the
 * <em>same</em> shape as a missing result, so it is mapped to {@code DENY_ALL} — never {@code ALLOW_ALL}.
 * {@code ALLOW_ALL} is produced only by an explicit, satisfiable, condition-free residual (a query whose
 * conjunction is empty). An absent/ambiguous compile output therefore denies, by construction.
 *
 * <p>No OPA types leak through this record — it is pure data, Spring-free, JSON-free.
 *
 * <h2>The "fully supported" flag (the allowlist escape hatch)</h2>
 * When the residual contained an expression the translator could not map to SQL, {@link #fullySupported()}
 * is {@code false}. The residual still <strong>defaults to {@link Decision#DENY_ALL}</strong> (fail-closed),
 * but the flag lets a caller with the post-fetch allowlist enabled choose an <em>exact batch re-check</em>
 * over the candidate set instead of an empty list — never a wider one. When the fallback is off, the
 * unsupported residual simply denies. A fully-translatable residual has {@code fullySupported == true}.
 *
 * @param decision       which of the three outcomes this residual is
 * @param clauses        the DNF disjuncts (each a conjunction of conditions); meaningful only for
 *                       {@link Decision#CONDITIONAL}, otherwise an empty list
 * @param fullySupported {@code false} iff the compile output contained an expression the translator could
 *                       not represent (so a batch finish may be needed); {@code true} otherwise
 */
public record PartialResult(Decision decision, List<Conjunction> clauses, boolean fullySupported) {

    /** The three outcomes a compiled residual collapses to. */
    public enum Decision {
        /** The query holds for every row → no predicate (match all). */
        ALLOW_ALL,
        /** The query can never hold → an always-false predicate (match none). The fail-closed value. */
        DENY_ALL,
        /** The query holds for rows satisfying {@link PartialResult#clauses()} (DNF). */
        CONDITIONAL
    }

    public PartialResult {
        clauses = clauses == null ? List.of() : List.copyOf(clauses);
    }

    /** A fully-supported residual (the common case): the translation is complete. */
    public PartialResult(Decision decision, List<Conjunction> clauses) {
        this(decision, clauses, true);
    }

    /** The "match everything" residual — the query holds for every row (no predicate). */
    public static PartialResult allowAll() {
        return new PartialResult(Decision.ALLOW_ALL, List.of());
    }

    /**
     * The "match nothing" residual — the query holds for no row. <strong>This is the fail-closed
     * value</strong>: every compile/transport/parse failure resolves to it (fully supported — there was
     * simply nothing to satisfy).
     */
    public static PartialResult denyAll() {
        return new PartialResult(Decision.DENY_ALL, List.of());
    }

    /**
     * A fail-closed deny that is <strong>flagged not-fully-SQL</strong> — the compile output contained an
     * expression the translator could not represent. With the post-fetch allowlist on, a caller may run an
     * exact batch re-check rather than returning empty; with it off, this is an ordinary deny.
     */
    public static PartialResult unsupported() {
        return new PartialResult(Decision.DENY_ALL, List.of(), false);
    }

    /**
     * A conditional residual over the given DNF disjuncts. An empty or null disjunct list is treated as
     * {@link #denyAll()} (no disjunct can be satisfied), preserving the fail-closed posture.
     */
    public static PartialResult conditional(List<Conjunction> clauses) {
        if (clauses == null || clauses.isEmpty()) {
            return denyAll();
        }
        return new PartialResult(Decision.CONDITIONAL, clauses);
    }
}
