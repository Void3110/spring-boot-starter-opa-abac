package dev.dmitriikonovalov.opaabac.core;

import java.util.Objects;

/**
 * A concrete resolve target — the {@code (resourceType, resourceId)} pair a batch role resolution
 * ({@link RoleDefinitionSupplier#lookupAll}) answers one entry for (ADR 0024). Value semantics are
 * the batch/memo key: two targets are the same entry iff type and id both match.
 *
 * <p>Both parts are required: the batch form exists for pages of <em>instances</em> (each row's
 * governing root); a type-level check ({@code resourceId == null}) stays on the single
 * {@link RoleDefinitionSupplier#lookup} — a null id has no wire encoding
 * ({@code target=<type>:<id>}) and would poison strict completeness.
 *
 * @param resourceType the resource type (e.g. {@code "catalog"}); never {@code null}
 * @param resourceId   the resource id; never {@code null}
 */
public record ResolveTarget(String resourceType, String resourceId) {

    public ResolveTarget {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
    }
}
