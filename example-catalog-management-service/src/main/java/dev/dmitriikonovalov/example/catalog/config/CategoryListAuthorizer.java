package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 */
@Component
public class CategoryListAuthorizer {

    private final CategoryRepository categories;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final AbacQueryService queryService;

    public CategoryListAuthorizer(
            CategoryRepository categories,
            RoleDefinitionSupplier roleDefinitionSupplier,
            AbacQueryService queryService) {
        this.categories = categories;
        this.roleDefinitionSupplier = roleDefinitionSupplier;
        this.queryService = queryService;
    }

    /**
     * The Categories under {@code catalogId} (optionally under {@code parentId}) the current subject may
     * read, filtered in SQL by the partial-eval residual.
     */
    public List<CategoryEntity> readable(UUID catalogId, UUID parentId) {
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            return List.of(); // unauthenticated → empty (the @OpaPreAuthorize gate already ran)
        }

        // Resolve the role on the GOVERNING CATALOG (the team target), exactly as CategoryAuthorizer does.
        RoleDefinition roleDefinition = roleDefinitionSupplier
                .lookup(subject.id(), "catalog", catalogId.toString())
                .orElse(null);

        // The query context: the resource is UNKNOWN (it's the row being filtered); only the type is set
        // so the policy path resolves to `category`.
        AbacContext queryContext = new AbacContext(
                subject,
                "category:read",
                new AbacContext.Resource("category", null, Map.of()),
                roleDefinition,
                Map.of());

        Specification<CategoryEntity> scope = scopedTo(catalogId, parentId);
        return queryService.findAuthorized(categories, scope, queryContext);
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
