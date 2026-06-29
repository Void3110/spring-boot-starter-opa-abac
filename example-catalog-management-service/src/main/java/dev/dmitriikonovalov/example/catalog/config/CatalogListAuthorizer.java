package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The <b>list-filtering</b> authority for the catalog (root) list — example-app code on the library's
 * public {@link AbacQueryService} (Slice B4, ADR 0018). It is the <b>sole</b> authority for
 * {@code GET /catalogs}: there is no coarse {@code @OpaPreAuthorize(catalog:list)} gate on the endpoint
 * anymore (a type-level list resolves no per-resource role, and after B4 there is no realm fallback, so
 * such a gate would deny every membership-driven caller). Authorization is instead the
 * <b>governed-scope ∧ filter-residual</b> cut here, fail-closed to an empty page.
 *
 * <h2>How catalog isolation differs from category isolation</h2>
 * A Category is governed by its <em>parent Catalog</em>, so {@code CategoryListAuthorizer} resolves the
 * role on that parent and scopes by {@code catalogId}. A Catalog is a <b>root</b> — its visibility is a
 * pure <em>membership</em> question (which team governs it, am I a member?), which OPA partial-eval cannot
 * see on the row. So this authorizer composes two things:
 * <ol>
 *   <li><b>The governed base scope</b> — {@link GovernedScopeResolver#governedScope} →
 *       {@code id IN (the catalog ids I govern via membership)}, or an always-false Specification when I
 *       govern none / the membership source is unreachable. This is the AND-gate nothing escapes.</li>
 *   <li><b>The OPA {@code filter} residual</b> — the role-def-only catalog {@code filter} rule, which
 *       partial-evaluates to ALLOW_ALL when the role grants {@code list} and DENY_ALL otherwise. Composed
 *       by {@link AbacQueryService#findAuthorized} as {@code scope.and(residual)}.</li>
 * </ol>
 * Result: {@code governedScope ∧ ALLOW_ALL} = exactly the governed set; {@code governedScope ∧ DENY_ALL}
 * = empty; and a subject governing nothing is empty before the residual even matters.
 *
 * <h2>Which role drives the residual</h2>
 * The {@code filter} is compiled once with one role definition, but a multi-team subject may hold a
 * different role per governed catalog. Because every governed catalog is one the subject is a member of
 * (the governed ids already encode membership), the residual only needs to answer the COARSE "does a
 * membership role grant {@code list}". We therefore resolve the role on the <b>first governed id</b>
 * ({@code governedIds.get(0)}) — any one suffices for the coarse {@code list} question, and the per-row
 * membership cut is already done by the governed scope. The governed ids come from a <b>single</b>
 * membership fetch ({@link GovernedScopeResolver#governedIds}) that also builds the scope, so there is no
 * table scan and no second round-trip. A team member's role always grants at least READ ({@code list}); a
 * role that explicitly denies {@code list} compiles to DENY_ALL → empty (fail-closed).
 *
 * <h2>Fail-closed (ADR 0018 §5)</h2>
 * Unauthenticated → empty page. No {@link GovernedScopeResolver} bean (e.g. the demo profile, where
 * membership isn't wired) → empty page (the {@code ObjectProvider} resolves to absent → {@code denyAll}).
 * A governed scope that is empty/always-false → empty page. A role-source outage resolving the residual
 * role → empty page (never the otherwise-uncaught throw becoming a 500). In every branch the floor is the
 * empty page, never the whole table.
 */
@Component
public class CatalogListAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(CatalogListAuthorizer.class);

    private final CatalogRepository catalogs;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final AbacQueryService queryService;
    /** Present only when membership-driven isolation is wired (catalog.role-source=http); absent → empty. */
    private final ObjectProvider<GovernedScopeResolver> governedScopeResolver;

    public CatalogListAuthorizer(
            CatalogRepository catalogs,
            RoleDefinitionSupplier roleDefinitionSupplier,
            AbacQueryService queryService,
            ObjectProvider<GovernedScopeResolver> governedScopeResolver) {
        this.catalogs = catalogs;
        this.roleDefinitionSupplier = roleDefinitionSupplier;
        this.queryService = queryService;
        this.governedScopeResolver = governedScopeResolver;
    }

    /**
     * The page of catalogs the current subject governs (via team membership) and whose membership role
     * grants {@code list}, windowed by {@code pageable}. The page's {@code totalElements} is the subject's
     * authorized total (the governed-scope ∧ residual cut applies to the count exactly as to the rows).
     * The {@code pageable} must carry the service's fixed total order; the library seam rejects an
     * unsorted one.
     */
    public Page<CatalogEntity> readable(Pageable pageable) {
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            return Page.empty(pageable); // unauthenticated → empty
        }

        GovernedScopeResolver resolver = governedScopeResolver.getIfAvailable();
        if (resolver == null) {
            // No membership-driven isolation wired (e.g. demo profile) → fail-closed to empty. The library
            // default (a plain @OpaPreAuthorize(catalog:list)) is unaffected for non-adopters; this
            // example bean simply has nothing to scope by, so it returns nothing rather than the table.
            log.debug("catalog list empty: no GovernedScopeResolver bean (membership isolation not wired)");
            return Page.empty(pageable);
        }

        // ONE membership fetch: the governed catalog ids (distinct, empty on "governs nothing"/breach —
        // never throws). The ids drive BOTH the base scope and the residual-role resolution, so there is no
        // table scan and no second round-trip.
        List<UUID> governedIds = resolver.governedIds(subject.id(), "catalog");
        if (governedIds.isEmpty()) {
            return Page.empty(pageable); // governs nothing → empty page (fail-closed; scope would be too)
        }

        // Resolve the role on the FIRST governed catalog to drive the role-def-only `filter` residual: any
        // governed catalog's membership role answers the COARSE "may I list catalogs" (the per-row cut is
        // the governed scope itself). A role-source outage → empty page (fail-closed, never a 500).
        RoleDefinition roleDefinition;
        try {
            roleDefinition = roleDefinitionSupplier
                    .lookup(subject.id(), "catalog", governedIds.get(0).toString())
                    .orElse(null);
        } catch (RoleResolutionException e) {
            log.debug("catalog list denied: role-source outage ({})", e.getClass().getSimpleName());
            return Page.empty(pageable);
        }
        // A governed catalog whose role no longer resolves (revoked between the two calls) → null role →
        // the `filter` compiles to DENY_ALL → empty page. Fail-closed, not a leak.

        // The governed base scope: id IN (governedIds) — the AND-gate nothing escapes.
        Specification<CatalogEntity> scope = (root, query, cb) -> root.get("id").in(governedIds);

        // The query context: the resource is UNKNOWN (it's the row being filtered); only the type is set so
        // the policy path resolves to `catalog`. Catalogs are roots → subtreeSpec = null.
        AbacContext queryContext = new AbacContext(
                subject,
                "catalog:list",
                new AbacContext.Resource("catalog", null, Map.of()),
                roleDefinition,
                Map.of());

        return queryService.findAuthorized(catalogs, scope, queryContext, null, pageable);
    }

    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null;
    }
}
