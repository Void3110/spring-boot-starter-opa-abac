package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Static, data-driven {@link RoleDefinitionSupplier} for the demo. Maps the caller's Keycloak realm
 * roles to a {@link RoleDefinition} whose {@code permissions} the OPA policy decides on.
 *
 * <p>This is the clean stand-in for a real authority: it overrides the starter's
 * {@code NoOpRoleDefinitionSupplier} (the starter backs off via {@code @ConditionalOnMissingBean}).
 * Phase 4 swaps in {@link HttpRoleDefinitionSupplier} (calling the user-management service) — a single
 * bean change, selected by the {@code catalog.role-source} property: {@code demo} (default, this bean)
 * vs {@code http}. Everything downstream depends only on the {@link RoleDefinitionSupplier} interface.
 *
 * <ul>
 *   <li>{@code catalog-viewer} → read on catalog/category/product</li>
 *   <li>{@code catalog-editor} → read + write (create/update/delete) on catalog/category/product</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "catalog.role-source", havingValue = "demo", matchIfMissing = true)
public class DemoRoleDefinitionSupplier implements RoleDefinitionSupplier {

    private static final List<String> TYPES = List.of("catalog", "category", "product");

    private static final RoleDefinition VIEWER = new RoleDefinition(
            "catalog-viewer",
            Map.of("role_level", 10),
            permissionsFor(List.of("read")));

    private static final RoleDefinition EDITOR = new RoleDefinition(
            "catalog-editor",
            Map.of("role_level", 20),
            permissionsFor(List.of("read", "write")));

    @Override
    public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
        List<String> roles = currentRoles();
        if (roles.contains("catalog-editor")) {
            return Optional.of(EDITOR);
        }
        if (roles.contains("catalog-viewer")) {
            return Optional.of(VIEWER);
        }
        return Optional.empty();
    }

    private static List<String> currentRoles() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac) {
            return abac.getSubject().roles();
        }
        return List.of();
    }

    private static Map<String, List<String>> permissionsFor(List<String> verbs) {
        return TYPES.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(t -> t, t -> verbs));
    }
}
