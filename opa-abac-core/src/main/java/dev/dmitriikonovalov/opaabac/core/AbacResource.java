package dev.dmitriikonovalov.opaabac.core;

import java.util.Map;
import java.util.Optional;

/**
 * A domain object that can be authorized: exposes its ABAC resource type, id, and attributes
 * so the framework can build an {@link AbacContext.Resource} without coupling to the domain type.
 */
public interface AbacResource {

    String abacResourceType();

    String abacResourceId();

    default Map<String, Object> abacAttributes() {
        return Map.of();
    }

    /**
     * This object's immediate parent in a resource hierarchy, if any — the declarative source of truth
     * for one hop up the tree (e.g. a Category's governing Catalog, a Product's Category).
     *
     * <p>The default is {@link Optional#empty()}: a non-hierarchical resource declares no parent and the
     * framework treats it as authorized on itself, exactly as before. A hierarchical resource overrides
     * this to return its immediate {@link ParentRef}; the framework walks those hops to assemble the
     * ancestor chain supplied to OPA as {@code input.resource.ancestors}. This is purely additive — every
     * existing implementation keeps the empty default and behaves unchanged.
     *
     * @return the immediate parent, or empty when this resource has no inheritable lineage
     */
    default Optional<ParentRef> abacParent() {
        return Optional.empty();
    }
}
