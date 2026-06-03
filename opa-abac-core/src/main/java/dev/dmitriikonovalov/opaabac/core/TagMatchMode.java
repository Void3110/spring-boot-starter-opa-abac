package dev.dmitriikonovalov.opaabac.core;

/**
 * How a role's {@link RoleDefinition#requiredTags() required tags} combine when more than one key is
 * required — the attribute-based grant's quantifier:
 *
 * <ul>
 *   <li>{@link #ANY_OF} — at least one required key is satisfied (existential; AWS {@code ForAnyValue:});</li>
 *   <li>{@link #ALL_OF} — every required key is satisfied (universal; AWS {@code ForAllValues:}).</li>
 * </ul>
 *
 * <p>The match itself is evaluated <em>in the policy</em> (Rego {@code some in} / {@code every}); this enum
 * is only the contract carried in the OPA input ({@code role_definition.match_mode}). {@link #ANY_OF} is
 * the default, so a single required key behaves the obvious way.
 */
public enum TagMatchMode {
    ANY_OF,
    ALL_OF
}
