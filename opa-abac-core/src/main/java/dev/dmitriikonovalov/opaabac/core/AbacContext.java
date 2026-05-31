package dev.dmitriikonovalov.opaabac.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Immutable ABAC evaluation context: the inputs to an authorization decision.
 *
 * <p>Subject (who), Action (what), Resource (on what), RoleDefinition (with what granted
 * permissions), Environment (in what circumstances). This is the framework-agnostic input model that
 * gets serialized as OPA {@code input}.
 *
 * <p>The {@code roleDefinition} is the decision backbone — a policy decides primarily on the action
 * verb being present in {@code role_definition.permissions[resource.type]}. It is nullable: when no
 * supplier resolves one, it is omitted from the serialized input and the policy may fall back to the
 * subject's roles.
 */
public record AbacContext(
        Subject subject,
        String action,
        Resource resource,
        @JsonProperty("role_definition") @JsonInclude(JsonInclude.Include.NON_NULL)
        RoleDefinition roleDefinition,
        Map<String, Object> environment) {

    public AbacContext {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    /** Convenience constructor for callers that have no role definition (e.g. before a supplier is wired). */
    public AbacContext(Subject subject, String action, Resource resource, Map<String, Object> environment) {
        this(subject, action, resource, null, environment);
    }

    /** The principal requesting access. */
    public record Subject(String id, List<String> roles, Map<String, Object> attributes) {
        public Subject {
            roles = roles == null ? List.of() : List.copyOf(roles);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /** The resource being accessed. */
    public record Resource(String type, String id, Map<String, Object> attributes) {
        public Resource {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
