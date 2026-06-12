package dev.dmitriikonovalov.opaabac.core;

import java.util.List;

/**
 * Supplies a resource's ancestor chain — <strong>root-first, leaf-excluded</strong>, the hierarchy
 * contract established by the ancestor-walk model (the chain travels to OPA as
 * {@code input.resource.ancestors}).
 *
 * <p>Applications do <em>not</em> implement this interface directly: the starter binds it to the
 * hierarchy module's {@code AncestorResolver} when one is configured. It exists as a separate core
 * interface so the authorization manager can consume ancestor chains while seeing only core types
 * (the security module must not depend on the data module).
 *
 * <p><strong>Failure semantics (fail-closed, narrowing):</strong> throwing makes the caller
 * <strong>collapse the chain to empty</strong> and decide direct-grant-only — never a partial chain,
 * never a stripped direct grant, and never a deny by itself. Note this is deliberately different from
 * {@link AbacResourceResolver}'s failure semantics (deny): ancestor failure only narrows the decision
 * basis, instance failure denies outright.
 */
@FunctionalInterface
public interface AncestorChainSupplier {

    /**
     * Resolve the ancestor chain for the given resource.
     *
     * @param resourceType the leaf resource's type
     * @param resourceId   the leaf resource's id
     * @return the chain root-first, excluding the leaf itself; empty for a root or flat resource
     */
    List<ParentRef> ancestorsOf(String resourceType, String resourceId);
}
