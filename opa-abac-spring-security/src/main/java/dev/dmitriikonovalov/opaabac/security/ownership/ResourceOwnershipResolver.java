package dev.dmitriikonovalov.opaabac.security.ownership;

import java.util.UUID;

/**
 * Resolves whether a subject <strong>owns</strong> (created) a resource that may live in <em>another</em>
 * service — the seam that closes target-squatting for self-service team creation (Slice B4, ADR 0019).
 *
 * <h2>Why a seam</h2>
 * With team membership the sole access path (ADR 0018), {@code createTeam(targetType, targetId)} binding an
 * arbitrary {@code targetId} with no ownership check is the primary way to break isolation: a user could
 * create a team on someone else's catalog and grant themselves owner access. The creator is recorded on the
 * <em>owning</em> service (the catalog's {@code created_by}), not the user-service where the team is
 * created — so the check is a <strong>cross-service</strong> question. This SPI answers it without
 * point-to-point coupling: the default {@link DiscoveryOwnershipResolver} discovers the owning service from
 * a config registry and reads the standard {@code created-by} contract.
 *
 * <h2>Fail-closed (the load-bearing invariant — ADR 0019)</h2>
 * Keyed by resource <strong>type</strong>; returns {@code false} on <em>every</em> non-affirmative outcome —
 * an unknown type (no owning service configured), an unreachable / erroring owning service, a {@code 404}
 * (no such resource), or a {@code createdBy} that does not match the caller. A breach is never an exception
 * that a caller might catch-and-allow: an implementation MUST resolve to {@code false}, never throw past its
 * own boundary. The use site ({@code createTeam}) maps {@code false} to a {@code 403}.
 *
 * <p>Lives in {@code opa-abac-spring-security} (not core): it needs no Spring Data type, and core stays
 * framework-free.
 */
public interface ResourceOwnershipResolver {

    /**
     * Does {@code subject} own (create) the resource {@code (resourceType, resourceId)}?
     *
     * @param subject      the caller's IdP {@code sub} (the same identity stored as the resource's
     *     {@code created_by})
     * @param resourceType the owning resource's ABAC type (e.g. {@code "catalog"}) — the registry key
     * @param resourceId   the owning resource's id
     * @return {@code true} only when the owning service confirms {@code createdBy == subject};
     *     {@code false} on every breach (unknown type / unreachable / 404 / mismatch) — fail-closed,
     *     never throwing
     */
    boolean isOwner(String subject, String resourceType, UUID resourceId);
}
