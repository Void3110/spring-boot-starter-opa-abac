package dev.dmitriikonovalov.opaabac.core;

import java.util.Objects;

/**
 * One leaf condition over a row attribute, in a {@link Conjunction} of a {@link PartialResult} residual.
 *
 * <p>The {@code path} is the dotted resource-attribute path the row must be tested on, relative to the
 * resource. Two families exist:
 *
 * <ul>
 *   <li>{@code "tags.<key>"} — a value in the resource's tag map (the JSONB {@code tags} column in the
 *       data-filtering translation);</li>
 *   <li>an <em>intrinsic</em> path with no {@code tags.} prefix (e.g. {@code "categoryId"}) — a plain
 *       column on the entity.</li>
 * </ul>
 *
 * <p>The {@link Operator} set is deliberately <strong>small and closed</strong>. A residual expression
 * that does not map onto one of these operators is not silently dropped — it forces the whole
 * {@link PartialResult} to fail closed ({@link PartialResult#denyAll()}). Narrow-but-correct beats
 * wide-but-wrong: a mistranslated predicate is a silent data leak.
 *
 * @param path     the dotted resource-attribute path (e.g. {@code "tags.region"}, {@code "categoryId"})
 * @param operator the comparison, from the closed set
 * @param value    the literal operand: a scalar for {@code EQ}/{@code NEQ}/{@code CONTAINS}, or a
 *                 {@code List} for {@code IN}
 */
public record Condition(String path, Operator operator, Object value) {

    /**
     * The closed set of comparisons the residual translator understands. Anything outside this set
     * fails closed.
     */
    public enum Operator {
        /** Scalar equality: the attribute equals {@code value}. */
        EQ,
        /** Scalar inequality: the attribute differs from {@code value}. */
        NEQ,
        /** Set membership: the attribute is one of the {@code List} {@code value}. */
        IN,
        /** Array membership: the attribute is an array containing the scalar {@code value} (JSONB {@code ?}). */
        CONTAINS
    }

    public Condition {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(operator, "operator");
    }

    /** True when this condition tests a JSONB tag value (path prefixed {@code "tags."}). */
    public boolean isTagPath() {
        return path.startsWith("tags.");
    }

    /** The tag key for a {@link #isTagPath() tag path} (the part after {@code "tags."}); else the whole path. */
    public String tagKey() {
        return isTagPath() ? path.substring("tags.".length()) : path;
    }
}
