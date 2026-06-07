package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalAuthorizer;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * A <b>per-instance, load-then-check</b> authorizer for a Category — the decision needs the resource's
 * <em>tags</em> (only known after the entity is loaded), which the type-level {@code @OpaPreAuthorize}
 * cannot supply.
 *
 * <p>Phase 5.5-A: this now delegates to the library's {@link HierarchicalAuthorizer}, which walks the
 * <b>full N-level ancestor chain</b> (a Category inherits from its parent Category and ultimately its
 * Catalog) rather than the previous single hard-coded one-step hop. The role is resolved once on the
 * governing root, the leaf's tags reach the policy, and the decision is
 * {@code direct OR (walk_ok AND inherited)} with deny-overrides — all fail-closed in the library.
 */
@Component
public class CategoryAuthorizer {

    private final HierarchicalAuthorizer hierarchicalAuthorizer;

    public CategoryAuthorizer(HierarchicalAuthorizer hierarchicalAuthorizer) {
        this.hierarchicalAuthorizer = hierarchicalAuthorizer;
    }

    /** Authorize {@code <category>:<verb>} on a loaded Category, or throw {@link AccessDeniedException}. */
    public void require(String verb, CategoryEntity category) {
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        if (!hierarchicalAuthorizer.isAllowed(subject, verb, category)) {
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
