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

    /**
     * The resource being accessed.
     *
     * <p>The {@code ancestors} list carries the resource's ancestor chain for hierarchical
     * authorization — <b>root-first and leaf-excluded</b> (the leaf is already this resource's own
     * {@code type}/{@code id}). It serializes as {@code input.resource.ancestors} and is
     * <em>omitted when empty</em> ({@code @JsonInclude} {@code NON_EMPTY}), so a non-hierarchical
     * resource serializes byte-for-byte as before. The three-argument constructor (no ancestors) keeps
     * every prior caller compiling unchanged.
     *
     * <p>The {@code rootAttributes} component carries the <b>governing root's</b> full tag map
     * (serialized {@code input.resource.root_attributes}, ADR 0032), so a policy can gate a child
     * decision on ancestor state without tag inheritance or ancestor entity loads. It has <b>three
     * distinguishable states, and keeping them distinguishable is the whole contract</b>:
     *
     * <ul>
     *   <li><b>absent</b> ({@code null}) — enrichment failed, or the caller never attempts it: the
     *       root's state is <em>unproven</em>;</li>
     *   <li><b>{@code &#123;&#125;}</b> — the root was fetched and carries no tags;</li>
     *   <li><b>populated</b> — the root was fetched and is tagged.</li>
     * </ul>
     *
     * <p>Hence {@code NON_NULL} and <b>never {@code NON_EMPTY}</b>: under {@code NON_EMPTY} an untagged
     * root's empty map would vanish from the wire and become indistinguishable from a failed fetch —
     * merging the two states this field exists to separate. For the same reason the compact constructor
     * defends the copy <b>null-preservingly</b> for this one component, unlike {@code attributes} and
     * {@code ancestors}, whose null-to-empty normalization would turn an enrichment failure into a
     * confident "the root has no tags".
     *
     * <p>Policy-author trap worth repeating (ADR 0032): testing the field with a bare
     * {@code not root_attributes.env == "production"} reads naturally and is <b>wrong</b> — an absent
     * value passes a negated comparison in Rego. The absent state needs its own clause.
     */
    public record Resource(
            String type,
            String id,
            Map<String, Object> attributes,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ParentRef> ancestors,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("root_attributes")
            Map<String, Object> rootAttributes) {

        public Resource {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            ancestors = ancestors == null ? List.of() : List.copyOf(ancestors);
            // Null-PRESERVING on purpose: null means "unproven", an empty map means "fetched, untagged".
            rootAttributes = rootAttributes == null ? null : Map.copyOf(rootAttributes);
        }

        /**
         * Convenience constructor for a resource with <b>no ancestor chain</b> — the prior shape. Keeps
         * every existing caller compiling unchanged and serializing byte-for-byte as before.
         */
        public Resource(String type, String id, Map<String, Object> attributes) {
            this(type, id, attributes, List.of(), null);
        }

        /**
         * Convenience constructor for a hierarchical resource with <b>no root enrichment</b> — the shape
         * before ADR 0032. Serializes byte-for-byte as before: the field is absent, not empty.
         */
        public Resource(
                String type, String id, Map<String, Object> attributes, List<ParentRef> ancestors) {
            this(type, id, attributes, ancestors, null);
        }
    }
}
