package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
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
 * The <b>list-filtering</b> counterpart to {@link CategoryAuthorizer} — example-app code built on the
 * library's public {@link AbacQueryService}, not a library change. Where {@code CategoryAuthorizer} answers
 * "may this subject read <em>this</em> Category?" by loading it and asking OPA, this answers "of the
 * Categories under this Catalog, <em>which</em> may the subject read?" by pushing OPA's partial-evaluation
 * residual into the SQL {@code WHERE} clause (the DB layer / layer 3).
 *
 * <p>Mirrors {@code CategoryAuthorizer} in two ways so the list and a single-GET agree:
 * <ol>
 *   <li><b>resolves the role on the governing parent</b> — a Category is governed by its Catalog's team,
 *       so the role is resolved for {@code (catalog, catalogId)};</li>
 *   <li>the residual is <b>AND-ed with</b> the existing path scope ({@code catalogId} [+ {@code parentId}]),
 *       never replacing it — so no Category from another catalog can leak in.</li>
 * </ol>
 *
 * <p>Fail-closed: an unauthenticated caller yields an empty list; a missing role definition compiles to a
 * deny-all residual (the {@code filter} rule has no subject-roles fallback) → empty list, never the full
 * table.
 *
 * <p><b>Hierarchy-aware (Slice 5.5-B).</b> When the hierarchy starter is enabled, this also asks the
 * {@link SubtreeSpecResolver} whether the subject's role on the governing Catalog <em>inheritably</em> grants
 * {@code category:list}; if so, the resolved {@code subtreeSpec} is passed into the <b>4-arg</b>
 * {@code findAuthorized} so the list is <em>widened</em> to the whole catalog subtree (still AND-ed with the
 * {@code catalogId} scope, still minus any {@code abac_deny} row). With hierarchy disabled (no resolver
 * bean), the {@code subtreeSpec} is simply absent and the list behaves exactly as the tag-only Phase-5 path.
 */
@Component
public class CategoryListAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(CategoryListAuthorizer.class);

    private final CategoryRepository categories;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final AbacQueryService queryService;
    /** Present only when the hierarchy starter is enabled; absent → tag-only list (no widening). */
    private final ObjectProvider<SubtreeSpecResolver> subtreeSpecResolver;

    public CategoryListAuthorizer(
            CategoryRepository categories,
            RoleDefinitionSupplier roleDefinitionSupplier,
            AbacQueryService queryService,
            ObjectProvider<SubtreeSpecResolver> subtreeSpecResolver) {
        this.categories = categories;
        this.roleDefinitionSupplier = roleDefinitionSupplier;
        this.queryService = queryService;
        this.subtreeSpecResolver = subtreeSpecResolver;
    }

    /**
     * The page of Categories under {@code catalogId} (optionally under {@code parentId}) the current
     * subject may read, filtered in SQL by the partial-eval residual and windowed by {@code pageable}
     * (Phase 5.95). The page's {@code totalElements} is the subject's <em>authorized</em> total — the
     * residual cut applies to the count exactly as to the rows. The {@code pageable} must carry the
     * service's fixed total order ({@code createdAt ASC, id ASC}); the library seam rejects an unsorted
     * one.
     */
    public Page<CategoryEntity> readable(UUID catalogId, UUID parentId, Pageable pageable) {
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            return Page.empty(pageable); // unauthenticated → empty (the @OpaPreAuthorize gate already ran)
        }

        // Resolve the role on the GOVERNING CATALOG (the team target), exactly as CategoryAuthorizer does.
        RoleDefinition roleDefinition;
        try {
            roleDefinition = roleDefinitionSupplier
                    .lookup(subject.id(), "catalog", catalogId.toString())
                    .orElse(null);
        } catch (RoleResolutionException e) {
            // B2: role-source outage → empty page (fail-closed; matches the no-role empty-list posture and
            // prevents the otherwise-uncaught throw becoming a 500). The outage never reaches the filter.
            log.debug("category list denied: role-source outage ({})", e.getClass().getSimpleName());
            return Page.empty(pageable);
        }

        // The query context: the resource is UNKNOWN (it's the row being filtered); only the type is set
        // so the policy path resolves to `category`.
        AbacContext queryContext = new AbacContext(
                subject,
                "category:list",
                new AbacContext.Resource("category", null, Map.of()),
                roleDefinition,
                Map.of());

        // 5.5-B: ask whether an inheritable Catalog grant should WIDEN the list to the whole catalog subtree.
        // Resolved on the SAME governing Catalog the role is resolved on, so the list and a single-GET agree.
        // Absent (hierarchy off / no inheritable grant) → null → the tag-only 3-arg behavior.
        Specification<CategoryEntity> subtreeSpec = resolveSubtreeSpec(subject, catalogId, roleDefinition);

        Specification<CategoryEntity> scope = scopedTo(catalogId, parentId);
        return queryService.findAuthorized(categories, scope, queryContext, subtreeSpec, pageable);
    }

    /**
     * The hierarchy widening for this catalog, or {@code null} when hierarchy is off / not granted.
     *
     * <p>Phase 6.5 (review fix): permission tokens are now COARSE categories, so the resolver is asked
     * for the {@code READ} token — {@code list} expands from {@code READ} and from no other category
     * (pinned by {@code CategoryListWideningParityTest}). The token check alone cannot see the role's
     * deny-overrides or tag requirement, and an over-widened {@code subtreeSpec} would leak rows (it is
     * OR-ed with the residual) — so widening is attempted only for a role that carries <b>no</b>
     * denial touching the governing-root type and <b>no</b> required tags. Anything subtler degrades
     * FAIL-CLOSED to the narrower tag-only filter (and its batch recheck, which is wildcard-, denial-
     * and tag-aware) — correct rows, just without the SQL widening.
     */
    private Specification<CategoryEntity> resolveSubtreeSpec(
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
                .<CategoryEntity>subtreeSpec(
                        subject, "category", new ParentRef("catalog", catalogId.toString()), "READ")
                .orElse(null);
    }

    /** The existing path scoping: catalogId [+ parentId]. AND-ed with the residual, never replaced. */
    private static Specification<CategoryEntity> scopedTo(UUID catalogId, UUID parentId) {
        Specification<CategoryEntity> scope = (root, query, cb) -> cb.equal(root.get("catalogId"), catalogId);
        if (parentId != null) {
            scope = scope.and((root, query, cb) -> cb.equal(root.get("parentId"), parentId));
        }
        return scope;
    }

    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null;
    }
}
