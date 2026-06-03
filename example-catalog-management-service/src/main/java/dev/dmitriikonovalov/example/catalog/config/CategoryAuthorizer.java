package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * A <b>per-instance, load-then-check</b> authorizer for a Category — the demo of tag-based grants that the
 * pre-invocation {@code @OpaPreAuthorize} (type-level) cannot do, because the decision needs the resource's
 * <em>tags</em> and the resource's tags are only known after the entity is loaded.
 *
 * <p>This is deliberately <b>example-app code</b> built on the library's public beans
 * ({@link OpaClient}, {@link RoleDefinitionSupplier}), not a library change. It does two things the
 * type-level path can't:
 *
 * <ol>
 *   <li><b>resolves the role against the governing parent</b> — a Category is governed by its Catalog's
 *       team, so the role is resolved for {@code (catalog, category.catalogId)}, not the category id (a
 *       small, demo-scoped hierarchy step; the general hierarchy walk is Phase 5);</li>
 *   <li><b>passes the loaded Category as the OPA resource</b> so its tags reach the policy
 *       ({@code input.resource.attributes}), where {@code category.rego}'s {@code tags_satisfied} matches
 *       them against the role's {@code required_tags}.</li>
 * </ol>
 *
 * <p>Fail-closed: an unauthenticated caller, an unresolved role, or any error denies (403). The OPA client
 * itself fails closed on transport errors.
 */
@Component
public class CategoryAuthorizer {

    private final OpaClient opaClient;
    private final RoleDefinitionSupplier roleDefinitionSupplier;

    public CategoryAuthorizer(OpaClient opaClient, RoleDefinitionSupplier roleDefinitionSupplier) {
        this.opaClient = opaClient;
        this.roleDefinitionSupplier = roleDefinitionSupplier;
    }

    /** Authorize {@code <category>:<verb>} on a loaded Category, or throw {@link AccessDeniedException}. */
    public void require(String verb, CategoryEntity category) {
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        // The role is resolved on the GOVERNING CATALOG (the parent the team targets), not the category id.
        RoleDefinition roleDefinition = roleDefinitionSupplier
                .lookup(subject.id(), "catalog", category.getCatalogId().toString())
                .orElse(null);

        AbacContext.Resource resource = new AbacContext.Resource(
                category.abacResourceType(), category.abacResourceId(), category.abacAttributes());
        AbacContext context = new AbacContext(
                subject, "category:" + verb, resource, roleDefinition, Map.of());

        boolean allowed;
        try {
            allowed = opaClient.allow(context);
        } catch (Exception e) {
            allowed = false; // fail-closed
        }
        if (!allowed) {
            throw new AccessDeniedException("Not authorized: category:" + verb);
        }
    }

    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null;
    }
}
