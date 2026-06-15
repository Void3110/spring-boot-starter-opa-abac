package dev.dmitriikonovalov.opaabac.core;

import java.util.Optional;

/**
 * Resolves the {@link RoleDefinition} that applies to a given subject and resource at decision time.
 *
 * <p>This is the seam that isolates the <em>source</em> of role definitions from the decision
 * mechanics. The library ships a {@link NoOpRoleDefinitionSupplier} default; an example app provides
 * a static, data-driven supplier; a real deployment swaps in one backed by an authority service —
 * each is a single-bean change because everything downstream depends only on this interface.
 *
 * <h2>Tri-state contract</h2>
 * {@link #lookup} distinguishes three outcomes — the third is what stops a role-source <em>outage</em>
 * from silently widening access:
 * <ul>
 *   <li>{@link Optional#of(Object) Optional.of(def)} — <strong>resolved</strong>: decide on this role.</li>
 *   <li>{@link Optional#empty()} — <strong>authoritative no-role</strong>: the subject genuinely has no
 *       role for this resource. A <em>designed signal</em> — a policy may fall back to the subject's
 *       realm roles. Retained, unchanged.</li>
 *   <li><strong>throws {@link RoleResolutionException}</strong> — <strong>outage</strong>: the role
 *       source was unavailable, so the result is <em>unknown</em>. The caller MUST fail closed (deny /
 *       no widening) and <strong>never</strong> fall back — an outage is not no-role.</li>
 * </ul>
 * The exception is unchecked, so this stays a {@code @FunctionalInterface}: a lambda or an in-process,
 * deterministic supplier that has no remote source to be unavailable simply never throws.
 */
@FunctionalInterface
public interface RoleDefinitionSupplier {

    /**
     * Look up the role definition for the given subject in the context of a resource.
     *
     * @param userId       the subject id (e.g. the JWT {@code sub})
     * @param resourceType the resource type being accessed
     * @param resourceId   the resource id, or {@code null} for type-level / create / list checks
     * @return the resolved role definition ({@link Optional#of}), or {@link Optional#empty()} if the
     *     subject <strong>authoritatively</strong> has no role for this resource (a designed signal — a
     *     policy may fall back to the subject's realm roles)
     * @throws RoleResolutionException if the role <strong>source</strong> was unavailable (an outage) so
     *     the result is <strong>unknown</strong> — the caller MUST fail closed (deny / no widening),
     *     never fall back. An in-process, deterministic supplier (no remote source) never throws.
     */
    Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId);
}
