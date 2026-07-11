package dev.dmitriikonovalov.opaabac.data.filter;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.data.model.Taggable;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolutionException;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import jakarta.persistence.criteria.Expression;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
 *   <li><strong>Never fail-open.</strong> A <em>failed</em> compile call ({@code fromError}) empties the
 *       whole list — including the {@code subtreeSpec} widening, which must never outlive the policy engine
 *       whose inherited-grant clause it mirrors. A <em>policy-derived</em> residual flagged not-fully-SQL
 *       yields empty <em>or</em> — with the allowlist on — an exact batch re-check over the survivors, never
 *       a wider set. A failed/empty {@code subtreeSpec} falls back to the narrower tag-only result. The
 *       {@code partialEval.enabled=false} kill-switch degrades to the coarse pre-Phase-5 path (scope + one
 *       {@code allow} check) — with the {@code abac_deny} filter still AND-ed, so the toggle never makes a
 *       denied row listable.</li>
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

    /**
     * The request-scoped attribute-snapshot cache the list path <strong>write-through</strong>s its
     * post-filter survivor rows into, so a downstream read-side consumer (action enrichment, Phase 6) reads
     * the same instance the query returned — no double-load, no attribute drift. {@code null} disables the
     * write-through entirely (byte-identical to the pre-Phase-6 behavior). Only ever <em>written</em> here;
     * the cache is an attribute snapshot, never a verdict, and an authorization decision never reads it.
     */
    private final AbacResourceCache resourceCache;

    /** Tag key carrying the operational deny-override flag (mirrors {@code category.rego}'s {@code denied}). */
    private static final String DENY_TAG = "abac_deny";

    /** Backward-compatible constructor (no hierarchy on the batch path, no enrichment write-through). */
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
        this(opaClient, specificationFactory, settings, ancestorResolver, null);
    }

    /**
     * @param resourceCache the request-scoped cache the list path write-through-populates with its
     *     post-filter survivor rows (Phase 6 action-enrichment feed); {@code null} disables the
     *     write-through (byte-identical to before). The write-through only <em>adds</em> a cache write — it
     *     never changes which rows are returned or any authorization decision.
     */
    public AbacQueryService(
            OpaClient opaClient,
            ResidualSpecificationFactory specificationFactory,
            PartialEvalSettings settings,
            AncestorResolver ancestorResolver,
            AbacResourceCache resourceCache) {
        this.opaClient = Objects.requireNonNull(opaClient, "opaClient");
        this.specificationFactory = Objects.requireNonNull(specificationFactory, "specificationFactory");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ancestorResolver = ancestorResolver; // nullable: hierarchy is opt-in
        this.resourceCache = resourceCache; // nullable: the enrichment write-through is opt-in
    }

    /**
     * Return the rows under {@code scope} that {@code queryContext}'s subject may see — the tag-only path
     * (no hierarchy widening). The <strong>signature is preserved byte-compatible</strong> (every existing
     * caller compiles unchanged); it delegates to the 4-arg overload with {@code subtreeSpec = null}.
     *
     * <p><strong>One deliberate behavioral change (a fail-closed hardening):</strong> the 4-arg path now
     * AND-s {@link #notDenied()} into the query, so a row whose tags carry {@code abac_deny == true} is
     * excluded from the list — even on this 3-arg path. Previously the tag-only {@code filter} residual did
     * not express the leaf deny, so a denied-but-tag-matching row could appear in a list while the
     * single-GET (whose {@code allow} rule applies deny-overrides) returned 403 for it. AND-ing
     * {@code notDenied} closes that list↔single-GET discrepancy (a fail-<em>open</em> gap), at the cost of
     * this one row-set change for any pre-existing caller relying on the old behavior.
     *
     * @param repo         the entity's repository (must support {@link Specification} queries)
     * @param scope        the caller's own scoping (e.g. {@code catalogId = ?}); AND-ed with the residual.
     *                     May be {@code null} for "no extra scope".
     * @param queryContext the subject + action + resourceType (resource UNKNOWN — it is the row)
     * @param <T>          the entity type (an {@link AbacResource}, so per-row contexts can be built)
     * @return the authorized rows, never {@code null}
     */
    public <T extends AbacResource> List<T> findAuthorized(
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
     * overrides the widening). A {@code null} {@code subtreeSpec} reduces this to {@code scope.and(tagResidual)
     * .and(notDenied)} — the tag-only path (now also deny-filtered; see the 3-arg overload's note).
     *
     * @param subtreeSpec the hierarchy widening (from a {@code SubtreeSpecResolver}); {@code null} for no
     *     widening (the tag-only path). A failed resolution should arrive as {@code null} or an empty
     *     (always-false) predicate — never wider.
     */
    public <T extends AbacResource> List<T> findAuthorized(
            JpaSpecificationExecutor<T> repo,
            Specification<T> scope,
            AbacContext queryContext,
            Specification<T> subtreeSpec) {

        if (!settings.enabled()) {
            // Kill-switch: degrade to the coarse pre-Phase-5 path — one type-level allow check, then the
            // caller's scope. Still fail-closed (deny → empty list), never fail-open. Hierarchy N/A.
            // The deny-override stays AND-ed even here: toggling the kill-switch must not make a row
            // carrying abac_deny == true listable when a single-GET for it returns 403.
            if (!opaClient.allow(queryContext)) {
                return List.of();
            }
            return cacheSurvivors(repo.findAll(scopeOnly(scope).and(notDenied())));
        }

        PartialResult residual = opaClient.compile(queryContext);

        if (residual.fromError()) {
            // The Compile call failed — there is no policy answer at all. The entire list fails closed,
            // including the subtreeSpec widening: the Java-side subtree gate mirrors the policy's
            // inherited-grant clause and must never outlive the policy engine it mirrors. (A policy-derived
            // DENY_ALL — an unsatisfiable tag branch — is different: the widening below may still apply.)
            return List.of();
        }

        // A not-fully-SQL residual with the allowlist on: fetch the scoped candidates, then batch-recheck for
        // the exact answer. The batch decision is hierarchy-aware (each per-row context carries ancestors),
        // so subtreeSpec is NOT applied here — it would be redundant with the per-row final_allow.
        if (!residual.fullySupported() && settings.allowlistFallback()) {
            List<T> candidates = repo.findAll(scopeOnly(scope));
            return cacheSurvivors(batchFilter(candidates, queryContext));
        }

        // Pure-SQL path: scope.and( tagResidual.or(subtreeSpec) ).and( notDenied ).
        return cacheSurvivors(repo.findAll(authorizedSpec(scope, residual, subtreeSpec)));
    }

    /**
     * The paged variant of {@link #findAuthorized(JpaSpecificationExecutor, Specification, AbacContext,
     * Specification)} (Phase 5.95): the same four query paths, the same compositions, plus a page window —
     * additive, so every unpaged caller compiles and behaves unchanged.
     *
     * <p>{@link Page#getTotalElements()} is the <strong>exact, subject-relative authorized total</strong>
     * on every path — never the page's own size, never an estimate:
     * <ul>
     *   <li><strong>Pure-SQL:</strong> {@code repo.findAll(combined, pageable)} — Spring Data issues the
     *       {@code COUNT} over the same combined specification.</li>
     *   <li><strong>Allowlist fallback:</strong> all scoped candidates are fetched <em>SQL-sorted</em>
     *       ({@code findAll(scope, pageable.getSort())} — so the page order is identical to the pure-SQL
     *       path's), batch-filtered (order-preserving), and the requested window sliced in memory; the
     *       total is the survivor count. The fetch-all cost is the path's existing Phase-5 degradation —
     *       pagination adds nothing to it; the in-memory slice is what keeps the count exact.</li>
     *   <li><strong>Kill-switch</strong> ({@code partialEval.enabled=false}): a coarse {@code allow}
     *       check, then {@code scope.and(notDenied)} paged — the deny-override stays AND-ed even degraded.</li>
     *   <li><strong>Failed compile</strong> ({@code fromError}): an empty page with total {@code 0} and
     *       <em>no repository call</em> — the fail-closed cut includes the count.</li>
     * </ul>
     *
     * <p><strong>The {@code Pageable} must carry a sort.</strong> Paginating without a total order is a
     * correctness bug — rows silently repeat or vanish across pages as the database reorders — so an
     * unsorted (or unpaged) {@code Pageable} is refused with an {@link IllegalArgumentException} before
     * any OPA or repository call. Callers pass a fixed total order (e.g. {@code createdAt ASC, id ASC}).
     *
     * @param pageable the page window; must be sorted (a total order), e.g.
     *     {@code PageRequest.of(page, perPage, Sort.by("createdAt").ascending().and(Sort.by("id").ascending()))}
     * @return the authorized page, never {@code null}; {@code getTotalElements()} is the subject's exact
     *     authorized total under {@code scope} (a past-the-end request returns an empty page with that
     *     same total)
     * @throws IllegalArgumentException if {@code pageable} carries no sort
     */
    public <T extends AbacResource> Page<T> findAuthorized(
            JpaSpecificationExecutor<T> repo,
            Specification<T> scope,
            AbacContext queryContext,
            Specification<T> subtreeSpec,
            Pageable pageable) {

        if (pageable.getSort().isUnsorted()) {
            throw new IllegalArgumentException(
                    "paged findAuthorized requires a sorted Pageable — pagination without a total order"
                            + " is nondeterministic (rows can repeat or vanish across pages)");
        }

        if (!settings.enabled()) {
            // Kill-switch: the same coarse degradation as the unpaged path, paged. Deny → empty page
            // (count 0, no repo call); the deny-override stays AND-ed so the toggle never makes a denied
            // row listable — or countable.
            if (!opaClient.allow(queryContext)) {
                return Page.empty(pageable);
            }
            return cacheSurvivors(repo.findAll(scopeOnly(scope).and(notDenied()), pageable));
        }

        PartialResult residual = opaClient.compile(queryContext);

        if (residual.fromError()) {
            // No policy answer at all → no rows AND no count: a failed compile must not leak how many
            // rows the subject could otherwise see.
            return Page.empty(pageable);
        }

        if (!residual.fullySupported() && settings.allowlistFallback()) {
            // Fetch the candidates SQL-sorted so the fallback pages the same sequence the pure-SQL path
            // would (path-independent order), batch-recheck, then slice the window in memory. The
            // survivor count IS the exact total — a short/all-false decision list narrows both the page
            // and the count, never widens.
            List<T> candidates = repo.findAll(scopeOnly(scope), pageable.getSort());
            List<T> allowed = batchFilter(candidates, queryContext);
            return cacheSurvivors(sliceInMemory(allowed, pageable));
        }

        // Pure-SQL path: the identical composition, paged — Spring Data derives the COUNT from it.
        return cacheSurvivors(repo.findAll(authorizedSpec(scope, residual, subtreeSpec), pageable));
    }

    /**
     * The one definition point of the pure-SQL authorization composition —
     * {@code scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )} — shared by the unpaged and paged
     * paths so the two can never drift: the widening OR-ed <em>inside</em> {@code scope.and(...)} (it
     * cannot escape scope), the deny AND-ed <em>outside</em> the OR (it overrides the widening).
     */
    private <T> Specification<T> authorizedSpec(
            Specification<T> scope, PartialResult residual, Specification<T> subtreeSpec) {
        Specification<T> tagResidual = specificationFactory.from(residual);
        Specification<T> widened =
                subtreeSpec == null ? tagResidual : Specification.where(tagResidual).or(subtreeSpec);
        return scopeOnly(scope).and(widened).and(notDenied());
    }

    /** The requested window over an already-filtered, already-ordered list; the list size is the total. */
    private static <T> Page<T> sliceInMemory(List<T> allowed, Pageable pageable) {
        long offset = pageable.getOffset();
        if (offset >= allowed.size()) {
            return new PageImpl<>(List.of(), pageable, allowed.size());
        }
        int from = (int) offset;
        int to = Math.min(from + pageable.getPageSize(), allowed.size());
        return new PageImpl<>(List.copyOf(allowed.subList(from, to)), pageable, allowed.size());
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
                    cb.function(
                            "jsonb_extract_path_text",
                            String.class,
                            root.get(Taggable.TAGS_ATTRIBUTE),
                            cb.literal(DENY_TAG));
            return cb.or(cb.isNull(denyText), cb.notEqual(denyText, "true"));
        };
    }

    /**
     * Write-through the post-filter survivor rows into the request-scoped {@link AbacResourceCache} (the
     * Phase-6 action-enrichment feed), returning the <em>same</em> list unchanged. A no-op when no cache is
     * wired. The rows written are exactly the ones being returned (the survivors) — denied/dropped rows are
     * never written, keeping the cache an authorized-snapshot store consistent with the gate's allow-only
     * write. Caches the same instance the query returned → no double-load, no attribute drift.
     */
    private <T extends AbacResource> List<T> cacheSurvivors(List<T> survivors) {
        cacheEach(survivors);
        return survivors;
    }

    /** {@link #cacheSurvivors(List)} for a page — writes the page's content rows, returns the same page. */
    private <T extends AbacResource> Page<T> cacheSurvivors(Page<T> survivors) {
        cacheEach(survivors.getContent());
        return survivors;
    }

    /** Write each survivor's {@code (type,id)} snapshot through to the cache; a no-op when none is wired. */
    private void cacheEach(Iterable<? extends AbacResource> survivors) {
        if (resourceCache == null) {
            return;
        }
        for (AbacResource survivor : survivors) {
            resourceCache.put(survivor.abacResourceType(), survivor.abacResourceId(), survivor);
        }
    }

    /** Drop the rows a per-row batch decision rejects (the allowlist finisher), hierarchy-aware. */
    private <T extends AbacResource> List<T> batchFilter(List<T> candidates, AbacContext queryContext) {
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
    private AbacContext withResource(AbacContext queryContext, AbacResource row) {
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
    private List<ParentRef> resolveAncestors(AbacResource row) {
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
        // Data JPA 4's neutral idiom: where(null) became ambiguous once where() gained overloads.
        return scope == null ? Specification.unrestricted() : scope;
    }

    /** Settings the query service honors — the starter binds its {@code partialEval} properties onto this. */
    public record PartialEvalSettings(boolean enabled, boolean allowlistFallback) {

        /** The defaults: partial-eval on, allowlist finisher on. */
        public static PartialEvalSettings defaults() {
            return new PartialEvalSettings(true, true);
        }
    }
}
