package dev.dmitriikonovalov.opaabac.core;

import java.util.List;

/**
 * One AND-group of a {@link PartialResult} residual — a single disjunct of the disjunctive normal form.
 *
 * <p>A row matches a {@code Conjunction} when it satisfies <em>every</em> {@link Condition} in it. The
 * surrounding {@link PartialResult} is the OR of its conjunctions: a row matches the residual when it
 * matches at least one conjunction.
 *
 * <p>An <strong>empty</strong> conjunction (no conditions) is vacuously true — it is how an
 * unconditional, condition-free residual is represented (it maps to {@link PartialResult.Decision#ALLOW_ALL}
 * at parse time).
 *
 * @param conditions the leaf conditions, all of which must hold (logical AND)
 */
public record Conjunction(List<Condition> conditions) {

    public Conjunction {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** True when this conjunction carries no conditions (vacuously true → contributes no predicate). */
    public boolean isEmpty() {
        return conditions.isEmpty();
    }
}
