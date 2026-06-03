package dev.dmitriikonovalov.opaabac.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The caller's effective role definition — the backbone of an ABAC decision.
 *
 * <p>Authorization here is driven <em>primarily</em> by the caller's role definition rather than by
 * raw token roles: a policy decides on {@code permissions[resourceType]} (the action verbs the role
 * grants for a resource type), with subject roles/tags acting only as an override or fallback. This
 * record is serialized into the OPA {@code input} as {@code role_definition}.
 *
 * <h2>Tag-based grants (optional, additive)</h2>
 * A role may additionally <em>require tags</em>: {@link #requiredTags} maps a tag key to the set of
 * acceptable values, and {@link #matchMode} says whether <em>any</em> ({@link TagMatchMode#ANY_OF}) or
 * <em>all</em> ({@link TagMatchMode#ALL_OF}) required keys must be satisfied by the resource's tags. The
 * match is evaluated in the policy ({@code some in} / {@code every}); these two fields just carry the
 * requirement in the OPA input as {@code required_tags} / {@code match_mode}.
 *
 * <p><b>Backward compatible.</b> Both tag fields are optional and <em>omitted from the serialized form
 * when unused</em> ({@code @JsonInclude} {@code NON_EMPTY}/{@code NON_NULL}), so a role without a tag
 * requirement serializes exactly as before and every existing policy/test is unaffected. The
 * three-argument constructor (no tag requirement) keeps every prior caller compiling unchanged.
 *
 * @param code         a stable identifier for the role (e.g. {@code "catalog-viewer"})
 * @param attributes   extensible role attributes (e.g. a role level); never {@code null}
 * @param permissions  {@code resourceType -> [allowed action verbs]}; never {@code null}
 * @param requiredTags {@code tagKey -> [acceptable values]}; empty when the role requires no tags
 * @param matchMode    {@link TagMatchMode#ANY_OF}/{@link TagMatchMode#ALL_OF}; {@code null} when no tags
 *                     are required (defaults to {@code ANY_OF} when a requirement is present)
 */
public record RoleDefinition(
        String code,
        Map<String, Object> attributes,
        Map<String, List<String>> permissions,
        @JsonProperty("required_tags") @JsonInclude(JsonInclude.Include.NON_EMPTY)
                Map<String, List<String>> requiredTags,
        @JsonProperty("match_mode") @JsonInclude(JsonInclude.Include.NON_NULL) TagMatchMode matchMode) {

    public RoleDefinition {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        permissions = permissions == null ? Map.of() : copyOfStringListMap(permissions);
        requiredTags = requiredTags == null ? Map.of() : copyOfStringListMap(requiredTags);
        // match_mode is meaningful only when a requirement is present; default it to ANY_OF then, and
        // keep it null otherwise so an untagged role serializes byte-for-byte as before.
        if (!requiredTags.isEmpty() && matchMode == null) {
            matchMode = TagMatchMode.ANY_OF;
        } else if (requiredTags.isEmpty()) {
            matchMode = null;
        }
    }

    /**
     * Convenience constructor for a role with <b>no tag requirement</b> — the prior shape. Keeps every
     * existing caller compiling unchanged and serializing byte-for-byte as before.
     */
    public RoleDefinition(
            String code, Map<String, Object> attributes, Map<String, List<String>> permissions) {
        this(code, attributes, permissions, Map.of(), null);
    }

    private static Map<String, List<String>> copyOfStringListMap(Map<String, List<String>> source) {
        return source.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue() == null ? List.of() : List.copyOf(e.getValue())));
    }
}
