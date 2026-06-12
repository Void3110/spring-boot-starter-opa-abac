package dev.dmitriikonovalov.opaabac.core;

import java.util.Optional;

/**
 * Resolves the resource <em>instance</em> behind a declared {@code (resourceType, resourceId)} so the
 * pre-invocation gate can decide on its real attributes instead of a bare reference.
 *
 * <p>An application registers <strong>one</strong> bean implementing this interface and dispatches on
 * {@code resourceType} internally (e.g. a switch over its repositories). With a resolver present, the
 * {@code @OpaPreAuthorize} authorization manager resolves the instance for every check that declares a
 * {@code resourceId} and sends its {@code abacAttributes()} (and ancestor chain) to OPA.
 *
 * <p><strong>Failure semantics (fail-closed):</strong> returning {@link Optional#empty()} or throwing
 * makes the caller <strong>deny</strong> the check — never a silent fallback to the attribute-less
 * context, which could skip attribute-keyed deny rules (i.e. widen). An unknown {@code resourceType}
 * must return empty. Note this is deliberately different from {@link AncestorChainSupplier}'s failure
 * semantics (collapse to direct-only): instance failure denies, ancestor failure only narrows.
 */
@FunctionalInterface
public interface AbacResourceResolver {

    /**
     * Resolve the instance behind the given reference.
     *
     * @param resourceType the resource type being accessed (e.g. {@code "category"})
     * @param resourceId   the resource id declared by the check
     * @return the resolved instance, or {@link Optional#empty()} when it does not exist or the type is
     *         not recognized — the caller denies
     */
    Optional<AbacDataObject> resolve(String resourceType, String resourceId);
}
