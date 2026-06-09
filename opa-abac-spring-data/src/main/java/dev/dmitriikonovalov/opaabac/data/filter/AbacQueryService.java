package dev.dmitriikonovalov.opaabac.data.filter;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolutionException;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import jakarta.persistence.criteria.Expression;
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
 * <h2>Hierarchy-aware (Slice 5.5-B)</h2>
 * The shipped OPA residual is <strong>tag-only</strong>. An inheritable grant on a list's governing root
 * widens which rows the list returns via a separate, app-built {@code subtreeSpec} (resolved by a
 * {@code SubtreeSpecResolver}, T2), composed here on the <strong>pure-SQL path</strong>:
 * <pre>combined = scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )</pre>
 * The {@code subtreeSpec} is OR-ed <em>inside</em> {@code scope.and(...)} so the widening can never escape
 * the caller's scope; {@code notDenied} is AND-ed <em>outside</em> the OR so a leaf deny overrides the
 * inherited widening too. The <strong>allowlist-batch path</strong> is independently hierarchy-aware: each
 * per-row {@link AbacContext} carries the row's ancestor chain, so the per-row OPA decision is the same
 * {@code final_allow = (direct OR inherited) AND NOT denied} as a single-GET (so {@code subtreeSpec} is not
 * applied there — it would be redundant).
 *
 * <h2>Two invariants</h2>
 * <ul>
 *   <li><strong>AND, never replace.</strong> The authorization specification is always
 *       {@code scope.and(...)} — the caller's own path scoping (e.g. {@code categoryId = ?}) is preserved,
 *       so no cross-scope row can leak (and the {@code subtreeSpec} widening cannot escape it).</li>
 *   <li><strong>Never fail-open.</strong> A compile failure yields {@code DENY_ALL} (empty page); a residual
 *       flagged not-fully-SQL yields empty <em>or</em> — with the allowlist on — an exact batch re-check over
 *       the survivors, never a wider set. A failed/empty {@code subtreeSpec} falls back to the narrower
 *       tag-only result. The {@code partialEval.enabled=false} kill-switch degrades to the coarse
 *       pre-Phase-5 path (scope + one {@code allow} check), still fail-closed.</li>
 * </ul>
 */
public class AbacQueryService {

    private final OpaClient opaClient;
    private final ResidualSpecificationFactory specificationFactory;
    private final PartialEvalSettings settings;

    /**
     * The hierarchy resolver used <em>only</em> on the allowlist-batch path, to build each per-row context
     * with its ancestor chain. May be {@code null} when hierarchy is disabled — the batch path then decides
     * each row on its direct grant only (fail-closed: never wider, just not hierarchy-aware).
     */
    private final AncestorResolver ancestorResolver;

    /** Tag key carrying the operational deny-override flag (mirrors {@code category.rego}'s {@code denied}). */
    private static final String DENY_TAG = "abac_deny";

    /** Backward-compatible constructor (no hierarchy on the batch path). */
    public AbacQueryService(
            OpaClient opaClient,
            ResidualSpecificationFactory specificationFactory,
            PartialEvalSettings settings) {
        this(opaClient, specificationFactory, settings, null);
    }

    /**
     * @param ancestorResolver the resolver used to attach the per-row ancestor chain on the allowlist-batch
     *     path (5.5-B); {@code null} disables hierarchy-awareness on that path (direct-grant-only, fail-closed)
     */
    public AbacQueryService(
            OpaClient opaClient,
            ResidualSpecificationFactory specificationFactory,
            PartialEvalSettings settings,
            AncestorResolver ancestorResolver) {
        this.opaClient = Objects.requireNonNull(opaClient, "opaClient");
        this.specificationFactory = Objects.requireNonNull(specificationFactory, "specificationFactory");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ancestorResolver = ancestorResolver; // nullable: hierarchy is opt-in
    }

    /**
     * Return the rows under {@code scope} that {@code queryContext}'s subject may see — the tag-only path
     * (no hierarchy widening). Preserved <strong>byte-compatible</strong>: delegates to the 4-arg overload
     * with {@code subtreeSpec = null}, so it behaves exactly as before.
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
        return findAuthorized(repo, scope, queryContext, null);
    }

    /**
     * Return the rows under {@code scope} that {@code queryContext}'s subject may see, optionally
     * <strong>widened</strong> by an inheritable-grant {@code subtreeSpec} (Slice 5.5-B).
     *
     * <p>On the pure-SQL path the composition is
     * {@code scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )} — the widening OR-ed <em>inside</em>
     * {@code scope.and(...)} (so it cannot escape scope), the deny AND-ed <em>outside</em> the OR (so it
     * overrides the widening). A {@code null} {@code subtreeSpec} reduces this to exactly the tag-only
     * 3-arg behavior.
     *
     * @param subtreeSpec the hierarchy widening (from a {@code SubtreeSpecResolver}); {@code null} for no
     *     widening (the tag-only path). A failed resolution should arrive as {@code null} or an empty
     *     (always-false) predicate — never wider.
     */
    public <T extends AbacDataObject> List<T> findAuthorized(
            JpaSpecificationExecutor<T> repo,
            Specification<T> scope,
            AbacContext queryContext,
            Specification<T> subtreeSpec) {

        if (!settings.enabled()) {
            // Kill-switch: degrade to the coarse pre-Phase-5 path — one type-level allow check, then the
            // caller's scope only. Still fail-closed (deny → empty list), never fail-open. Hierarchy N/A.
            if (!opaClient.allow(queryContext)) {
                return List.of();
            }
            return repo.findAll(scopeOnly(scope));
        }

        PartialResult residual = opaClient.compile(queryContext);

        // A not-fully-SQL residual with the allowlist on: fetch the scoped candidates, then batch-recheck for
        // the exact answer. The batch decision is hierarchy-aware (each per-row context carries ancestors),
        // so subtreeSpec is NOT applied here — it would be redundant with the per-row final_allow.
        if (!residual.fullySupported() && settings.allowlistFallback()) {
            List<T> candidates = repo.findAll(scopeOnly(scope));
            return batchFilter(candidates, queryContext);
        }

        // Pure-SQL path: scope.and( tagResidual.or(subtreeSpec) ).and( notDenied ).
        Specification<T> tagResidual = specificationFactory.from(residual);
        Specification<T> widened =
                subtreeSpec == null ? tagResidual : Specification.where(tagResidual).or(subtreeSpec);
        Specification<T> combined = (scope == null ? Specification.<T>where(null) : scope)
                .and(widened)
                .and(notDenied());
        return repo.findAll(combined);
    }

    /**
     * The deny-override as SQL — the {@code abac_deny IS DISTINCT FROM true} mirror of the Rego
     * {@code denied if input.resource.attributes.abac_deny == true} clause. AND-ed <em>outside</em> the
     * widening OR so a leaf deny overrides both the tag branch and the subtree branch.
     *
     * <p><b>Fail-closed match.</b> A row <em>absent</em> the {@code abac_deny} tag → {@code NULL IS DISTINCT
     * FROM true} → {@code TRUE} → not denied — matching the Rego {@code not denied} on an absent key, so the
     * list and a single-GET agree on "denied." {@code abac_deny} is a closed scalar boolean tag, always
     * SQL-expressible; a future non-scalar deny would route through the allowlist batch (not silently TRUE).
     */
    private static <T> Specification<T> notDenied() {
        // jsonb_extract_path_text(tags,'abac_deny') IS DISTINCT FROM 'true'
        //   ≡  (value IS NULL)  OR  (value <> 'true')   — exactly the IS-DISTINCT-FROM semantics.
        return (root, query, cb) -> {
            Expression<String> denyText =
                    cb.function("jsonb_extract_path_text", String.class, root.get("tags"), cb.literal(DENY_TAG));
            return cb.or(cb.isNull(denyText), cb.notEqual(denyText, "true"));
        };
    }

    /** Drop the rows a per-row batch decision rejects (the allowlist finisher), hierarchy-aware. */
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

    /**
     * A copy of the query context with the resource filled in from a concrete row — <strong>with the row's
     * ancestor chain</strong> when a resolver is configured, so the per-row OPA decision is the same
     * {@code final_allow = (direct OR inherited) AND NOT denied} as a single-GET (5.5-B).
     *
     * <p>Fail-closed: a per-row ancestor-resolution failure → <strong>empty</strong> ancestors → that row is
     * decided on its <em>direct</em> grant only (never wider). With no resolver, every row is direct-only.
     */
    private AbacContext withResource(AbacContext queryContext, AbacDataObject row) {
        List<ParentRef> ancestors = resolveAncestors(row);
        AbacContext.Resource resource = new AbacContext.Resource(
                row.abacResourceType(), row.abacResourceId(), row.abacAttributes(), ancestors);
        return new AbacContext(
                queryContext.subject(),
                queryContext.action(),
                resource,
                queryContext.roleDefinition(),
                queryContext.environment());
    }

    /** The row's ancestor chain (fail-closed to empty on any resolver failure or when hierarchy is off). */
    private List<ParentRef> resolveAncestors(AbacDataObject row) {
        if (ancestorResolver == null) {
            return List.of();
        }
        try {
            return ancestorResolver.ancestorsOf(row.abacResourceType(), row.abacResourceId());
        } catch (AncestorResolutionException e) {
            return List.of(); // fail-closed: decide this row on its direct grant only
        }
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
