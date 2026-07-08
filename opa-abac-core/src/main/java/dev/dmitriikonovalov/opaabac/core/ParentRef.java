package dev.dmitriikonovalov.opaabac.core;

import java.util.Objects;

/**
 * A neutral reference to a resource's immediate parent — its ABAC {@code (type, id)}.
 *
 * <p>This is the declarative source of truth for <em>one hop</em> up a resource hierarchy: a
 * {@link AbacResource} returns its immediate parent via {@link AbacResource#abacParent()}, and the
 * framework walks those hops to build the full ancestor chain that travels to OPA as
 * {@code input.resource.ancestors}. It carries no Spring or persistence concern — it is a plain value
 * type so {@code opa-abac-core} stays framework-agnostic.
 *
 * <p>Both components are required: a parent reference with a {@code null} type or id is meaningless and
 * would break the chain walk, so the compact constructor rejects them.
 *
 * @param type the parent resource's ABAC type (e.g. {@code "catalog"}); never {@code null}
 * @param id   the parent resource's ABAC id; never {@code null}
 */
public record ParentRef(String type, String id) {

    public ParentRef {
        Objects.requireNonNull(type, "ParentRef.type must not be null");
        Objects.requireNonNull(id, "ParentRef.id must not be null");
    }
}
