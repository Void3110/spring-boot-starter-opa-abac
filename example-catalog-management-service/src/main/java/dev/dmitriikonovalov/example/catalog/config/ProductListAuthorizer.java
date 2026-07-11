package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver;
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
 * The list-filtering cut for products — {@link CategoryListAuthorizer}'s shape applied to the leaf
 * type. Until products carried tags the list was a plain repository page (rows had no policy
 * variance); a taggable product breaks that justification — a tag-gated role must not see rows in
 * the list it may not read one-by-one — so the which-rows cut moves into SQL here exactly as it did
 * for categories: OPA's partial-evaluation residual AND-ed with the {@code categoryId} path scope.
 *
 * <p>Mirrors {@code CategoryListAuthorizer} point for point: the role resolves on the GOVERNING
 * CATALOG (the team target — a product's team is its catalog's, same as a category's); the residual
 * is AND-ed with the existing path scope, never replacing it; fail-closed on every branch
 * (unauthenticated / starter-off / role-source outage / no role definition → the empty page, never
 * the full table). See that class for the rationale prose; only the deltas are documented here.
 *
 * <p>Hierarchy widening (5.5-B): an inheritable Catalog grant may widen the list via the
 * {@code subtreeSpec} — resolved on the same governing catalog, gated to roles with no denial
 * touching the root type and no required tags (an over-widened spec would leak rows; anything
 * subtler degrades fail-closed to the tag-only filter + batch recheck).
 */
@Component
public class ProductListAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(ProductListAuthorizer.class);

    private final ProductRepository products;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    /** Absent when the starter is off (opa.abac.enabled=false, the unguarded-baseline rig) → empty. */
    private final ObjectProvider<AbacQueryService> queryService;
    /** Present only when the hierarchy starter is enabled; absent → tag-only list (no widening). */
    private final ObjectProvider<SubtreeSpecResolver> subtreeSpecResolver;

    public ProductListAuthorizer(
            ProductRepository products,
            RoleDefinitionSupplier roleDefinitionSupplier,
            ObjectProvider<AbacQueryService> queryService,
            ObjectProvider<SubtreeSpecResolver> subtreeSpecResolver) {
        this.products = products;
        this.roleDefinitionSupplier = roleDefinitionSupplier;
        this.queryService = queryService;
        this.subtreeSpecResolver = subtreeSpecResolver;
    }

    /**
     * The page of Products under {@code categoryId} the current subject may read, filtered in SQL by
     * the partial-eval residual and windowed by {@code pageable} (which must carry the service's fixed
     * total order — the library seam rejects an unsorted one). {@code totalElements} is the subject's
     * authorized total.
     */
    public Page<ProductEntity> readable(UUID catalogId, UUID categoryId, Pageable pageable) {
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            return Page.empty(pageable); // unauthenticated → empty (the @OpaPreAuthorize gate already ran)
        }

        AbacQueryService query = queryService.getIfAvailable();
        if (query == null) {
            log.debug("product list empty: no AbacQueryService bean (the starter is off)");
            return Page.empty(pageable);
        }

        // Resolve the role on the GOVERNING CATALOG (the team target), exactly as the category list does.
        RoleDefinition roleDefinition;
        try {
            roleDefinition = roleDefinitionSupplier
                    .lookup(subject.id(), "catalog", catalogId.toString())
                    .orElse(null);
        } catch (RoleResolutionException e) {
            log.debug("product list denied: role-source outage ({})", e.getClass().getSimpleName());
            return Page.empty(pageable);
        }

        // The query context: the resource is UNKNOWN (the row being filtered); only the type is set
        // so the policy path resolves to `product`.
        AbacContext queryContext = new AbacContext(
                subject,
                "product:list",
                new AbacContext.Resource("product", null, Map.of()),
                roleDefinition,
                Map.of());

        Specification<ProductEntity> subtreeSpec = resolveSubtreeSpec(subject, catalogId, roleDefinition);

        Specification<ProductEntity> scope = scopedTo(categoryId);
        return query.findAuthorized(products, scope, queryContext, subtreeSpec, pageable);
    }

    /**
     * The hierarchy widening for this catalog, or {@code null} when hierarchy is off / not granted —
     * the {@link CategoryListAuthorizer#readable} gating verbatim: no role, a tag-gated role, or a
     * denial touching the governing-root type never widens (fail-closed to the narrower filter).
     */
    private Specification<ProductEntity> resolveSubtreeSpec(
            AbacContext.Subject subject, UUID catalogId, RoleDefinition roleDefinition) {
        SubtreeSpecResolver resolver = subtreeSpecResolver.getIfAvailable();
        if (resolver == null) {
            return null; // hierarchy starter disabled → tag-only list
        }
        if (roleDefinition == null || !roleDefinition.requiredTags().isEmpty()) {
            return null; // no role / tag-gated role → never widen (fail-closed)
        }
        Map<String, List<String>> denied = roleDefinition.deniedActions();
        if (denied.containsKey("catalog") || denied.containsKey("*")) {
            return null; // a denial could subtract "list" from the root-type effective set → don't widen
        }
        return resolver
                .<ProductEntity>subtreeSpec(
                        subject, "product", new ParentRef("catalog", catalogId.toString()), "READ")
                .orElse(null);
    }

    /** The existing path scoping: the categoryId. AND-ed with the residual, never replaced. */
    private static Specification<ProductEntity> scopedTo(UUID categoryId) {
        return (root, query, cb) -> cb.equal(root.get("categoryId"), categoryId);
    }

    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null;
    }
}
