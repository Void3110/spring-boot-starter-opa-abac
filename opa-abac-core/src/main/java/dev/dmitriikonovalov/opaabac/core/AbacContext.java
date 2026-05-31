package dev.dmitriikonovalov.opaabac.core;

import java.util.List;
import java.util.Map;

/**
 * Immutable ABAC evaluation context: the inputs to an authorization decision.
 *
 * <p>Subject (who), Action (what), Resource (on what), Environment (in what circumstances).
 * This is the framework-agnostic input model that gets serialized as OPA {@code input}.
 */
public record AbacContext(
        Subject subject,
        String action,
        Resource resource,
        Map<String, Object> environment) {

    public AbacContext {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
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
