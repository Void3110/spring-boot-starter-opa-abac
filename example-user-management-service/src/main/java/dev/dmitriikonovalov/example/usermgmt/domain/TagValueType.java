package dev.dmitriikonovalov.example.usermgmt.domain;

/**
 * How a {@link TagDefinition}'s value is constrained.
 *
 * <ul>
 *   <li>{@link #STRING} — free-form text, optionally constrained by a {@code valuePattern} regex;</li>
 *   <li>{@link #ENUM} — a closed set of {@code allowedValues}; any value outside the set is illegal.</li>
 * </ul>
 *
 * <p>Generalizes the idea of regex/enum validation rules into <em>data on the row</em> (editable at
 * runtime) rather than code-registered rules.
 */
public enum TagValueType {
    STRING,
    ENUM
}
