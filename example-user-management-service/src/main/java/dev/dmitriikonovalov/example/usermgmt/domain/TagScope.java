package dev.dmitriikonovalov.example.usermgmt.domain;

/**
 * The reach of a {@link TagDefinition}.
 *
 * <ul>
 *   <li>{@link #GLOBAL} — a system-wide key, seeded and immutable through the API ({@code teamId == null});</li>
 *   <li>{@link #TEAM} — an owner/administrator-defined key scoped to one team ({@code teamId} set).</li>
 * </ul>
 *
 * <p>The split mirrors the system-vs-team distinction already used for role definitions: a key is unique
 * among globals and, independently, within a team (two partial unique indexes keyed on whether
 * {@code team_id} is null).
 */
public enum TagScope {
    GLOBAL,
    TEAM
}
