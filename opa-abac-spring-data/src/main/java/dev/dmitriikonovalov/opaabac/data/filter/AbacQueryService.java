package dev.dmitriikonovalov.opaabac.data.filter;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The seam that ties list-authorization together: build the query context → {@link OpaClient#compile}
 * the residual → translate to a {@link Specification} → run it (AND-ed with the caller's scope) → optionally
 * finish with a batch allowlist. Keeps a list controller thin: build the context, call
 * {@link #findAuthorized}, return the rows.
 *
 * <p>This is the DB layer (layer 3) of the three-layer enforcement model — the residual filters the rows in
 * SQL <em>inside</em> the coarse type-level {@code @OpaPreAuthorize} gate.
 *
 * <h2>Two invariants</h2>
 * <ul>
 *   <li><strong>AND, never replace.</strong> The authorization specification is always
 *       {@code scope.and(authzSpec)} — the caller's own path scoping (e.g. {@code categoryId = ?}) is
 *       preserved, so no cross-scope row can leak.</li>
 *   <li><strong>Never fail-open.</strong> A compile failure yields {@code DENY_ALL} (empty page); a residual
 *       flagged not-fully-SQL yields empty <em>or</em> — with the allowlist on — an exact batch re-check over
 *       the survivors, never a wider set. The {@code partialEval.enabled=false} kill-switch degrades to the
 *       coarse pre-Phase-5 path (scope + one {@code allow} check), still fail-closed.</li>
 * </ul>
 */
public class AbacQueryService {

    private final OpaClient opaClient;
    private final ResidualSpecificationFactory specificationFactory;
    private final PartialEvalSettings settings;

    public AbacQueryService(
            OpaClient opaClient,
            ResidualSpecificationFactory specificationFactory,
            PartialEvalSettings settings) {
        this.opaClient = Objects.requireNonNull(opaClient, "opaClient");
        this.specificationFactory = Objects.requireNonNull(specificationFactory, "specificationFactory");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Return the rows under {@code scope} that {@code queryContext}'s subject may see.
     *
     * @param repo         the entity's repository (must support {@link Specification} queries)
     * @param scope        the caller's own scoping (e.g. {@code catalogId = ?}); AND-ed with the residual.
     *                     May be {@code null} for "no extra scope".
     * @param queryContext the subject + action + resourceType (resource UNKNOWN — it is the row)
     * @param <T>          the entity type (an {@link AbacDataObject}, so per-row contexts can be built)
     * @return the authorized rows, never {@code null}
     */
    public <T extends AbacDataObject> List<T> findAuthorized(
            JpaSpecificationExecutor<T> repo, Specification<T> scope, AbacContext queryContext) {

        if (!settings.enabled()) {
            // Kill-switch: degrade to the coarse pre-Phase-5 path — one type-level allow check, then the
            // caller's scope only. Still fail-closed (deny → empty list), never fail-open.
            if (!opaClient.allow(queryContext)) {
                return List.of();
            }
            return repo.findAll(scopeOnly(scope));
        }

        PartialResult residual = opaClient.compile(queryContext);

        // A not-fully-SQL residual with the allowlist on: pre-filter by the recognized conjuncts (here:
        // nothing recognized → no pre-filter), fetch the candidates, then batch-recheck for the exact answer.
        if (!residual.fullySupported() && settings.allowlistFallback()) {
            List<T> candidates = repo.findAll(scopeOnly(scope));
            return batchFilter(candidates, queryContext);
        }

        Specification<T> authzSpec = specificationFactory.from(residual);
        Specification<T> combined = scope == null ? authzSpec : scope.and(authzSpec);
        return repo.findAll(combined);
    }

    /** Drop the rows a per-row batch decision rejects (the allowlist finisher). */
    private <T extends AbacDataObject> List<T> batchFilter(List<T> candidates, AbacContext queryContext) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<AbacContext> perRow = new ArrayList<>(candidates.size());
        for (T candidate : candidates) {
            perRow.add(withResource(queryContext, candidate));
        }
        List<Boolean> decisions = opaClient.allowAll(perRow);
        // Fail-closed: a mismatched/short decision list (the client returns all-false on error) drops rows.
        List<T> allowed = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (i < decisions.size() && Boolean.TRUE.equals(decisions.get(i))) {
                allowed.add(candidates.get(i));
            }
        }
        return allowed;
    }

    /** A copy of the query context with the resource filled in from a concrete row. */
    private static AbacContext withResource(AbacContext queryContext, AbacDataObject row) {
        AbacContext.Resource resource = new AbacContext.Resource(
                row.abacResourceType(), row.abacResourceId(), row.abacAttributes());
        return new AbacContext(
                queryContext.subject(),
                queryContext.action(),
                resource,
                queryContext.roleDefinition(),
                queryContext.environment());
    }

    private static <T> Specification<T> scopeOnly(Specification<T> scope) {
        return scope == null ? Specification.where(null) : scope;
    }

    /** Settings the query service honors — the starter binds its {@code partialEval} properties onto this. */
    public record PartialEvalSettings(boolean enabled, boolean allowlistFallback) {

        /** The defaults: partial-eval on, allowlist finisher on. */
        public static PartialEvalSettings defaults() {
            return new PartialEvalSettings(true, true);
        }
    }
}
