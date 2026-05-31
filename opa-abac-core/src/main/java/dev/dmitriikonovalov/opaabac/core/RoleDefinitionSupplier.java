package dev.dmitriikonovalov.opaabac.core;

import java.util.Optional;

/**
 * Resolves the {@link RoleDefinition} that applies to a given subject and resource at decision time.
 *
 * <p>This is the seam that isolates the <em>source</em> of role definitions from the decision
 * mechanics. The library ships a {@link NoOpRoleDefinitionSupplier} default; an example app provides
 * a static, data-driven supplier; a real deployment swaps in one backed by an authority service —
 * each is a single-bean change because everything downstream depends only on this interface.
 */
@FunctionalInterface
public interface RoleDefinitionSupplier {

    /**
     * Look up the role definition for the given subject in the context of a resource.
     *
     * @param userId       the subject id (e.g. the JWT {@code sub})
     * @param resourceType the resource type being accessed
     * @param resourceId   the resource id, or {@code null} for type-level / create / list checks
     * @return the role definition, or {@link Optional#empty()} if none applies
     */
    Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId);
}
