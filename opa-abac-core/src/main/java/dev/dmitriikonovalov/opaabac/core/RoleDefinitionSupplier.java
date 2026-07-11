package dev.dmitriikonovalov.opaabac.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Look up the role definitions for the given subject on <strong>N distinct targets at once</strong>
     * (ADR 0024) — the batch form behind pages whose rows have distinct governing roots, where
     * per-row {@link #lookup} calls would cost one round-trip each. The tri-state contract of
     * {@link #lookup} is <em>split, not redefined</em>:
     *
     * <ul>
     *   <li><strong>Entries are two-state.</strong> Each requested target maps to
     *       {@link Optional#of} (resolved) or {@link Optional#empty()} (authoritative no-role) —
     *       exactly {@link #lookup}'s value states, per entry.</li>
     *   <li><strong>The outage is whole-batch.</strong> If the answer for <em>any</em> target is
     *       unknown, the whole call throws {@link RoleResolutionException} — there is no per-entry
     *       error state, and a batch never yields partial roles. Every caller applies its existing
     *       fail-closed degrade for the whole batch.</li>
     *   <li><strong>Strict completeness.</strong> A successful return carries <em>exactly one entry
     *       per requested target</em> — no more, no fewer. An implementation that cannot honor that
     *       must throw (whole-batch outage), never return a partial or padded map.</li>
     *   <li>An <strong>empty target set returns an empty map</strong> without any lookup.</li>
     * </ul>
     *
     * <p>The default implementation loops over {@link #lookup} — every existing implementation
     * (including lambdas: the interface stays a {@code @FunctionalInterface}) remains valid and
     * correct, just per-target-cost. In the loop, any single throw aborts the whole batch
     * (consistent with whole-batch outage). Implementations backed by a remote source override this
     * with one wire exchange (the example's HTTP supplier); the library's memoizing decorator
     * serves hits from the request memo and forwards only the misses — as one batch — either way.
     *
     * <p>Known consumers: the enrichment advice's per-page root resolution
     * ({@code ActionEnrichmentAdvice}) and the request-scoped memo's batch integration
     * ({@code MemoizingRoleDefinitionSupplier}).
     *
     * @param userId  the subject id (e.g. the JWT {@code sub})
     * @param targets the distinct concrete targets to resolve (type-level checks stay on
     *     {@link #lookup})
     * @return an immutable map with exactly one two-state entry per requested target
     * @throws RoleResolutionException if the answer for any target is unknown — the whole batch is
     *     an outage; the caller MUST fail closed for all targets, never fall back
     */
    default Map<ResolveTarget, Optional<RoleDefinition>> lookupAll(String userId, Set<ResolveTarget> targets) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        Map<ResolveTarget, Optional<RoleDefinition>> resolved = new HashMap<>();
        for (ResolveTarget target : targets) {
            resolved.put(target, lookup(userId, target.resourceType(), target.resourceId()));
        }
        return Map.copyOf(resolved);
    }
}
