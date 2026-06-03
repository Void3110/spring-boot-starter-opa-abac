package dev.dmitriikonovalov.example.usermgmt.domain;

/**
 * Whether a {@link TagDefinition} carries one value or a set.
 *
 * <ul>
 *   <li>{@link #SINGLE} — exactly one scalar value (stored as a JSON string on the resource);</li>
 *   <li>{@link #MULTI} — a set of values (stored as a JSON string array on the resource).</li>
 * </ul>
 *
 * <p>The resource-side tag storage already serializes both a scalar string and a string array in the
 * same JSONB column, so multi-value assignment costs no schema change on the catalog side.
 */
public enum TagCardinality {
    SINGLE,
    MULTI
}
