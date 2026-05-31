package dev.dmitriikonovalov.opaabac.core;

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
 * @param code        a stable identifier for the role (e.g. {@code "catalog-viewer"})
 * @param attributes  extensible role attributes (e.g. a role level); never {@code null}
 * @param permissions {@code resourceType -> [allowed action verbs]}; never {@code null}
 */
public record RoleDefinition(
        String code,
        Map<String, Object> attributes,
        Map<String, List<String>> permissions) {

    public RoleDefinition {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        permissions = permissions == null
                ? Map.of()
                : permissions.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                e -> e.getValue() == null ? List.of() : List.copyOf(e.getValue())));
    }
}
