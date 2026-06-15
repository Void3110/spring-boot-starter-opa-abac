package dev.dmitriikonovalov.opaabac.core;

import java.util.Optional;

/**
 * Default {@link RoleDefinitionSupplier} that never resolves a role definition.
 *
 * <p>With this supplier in place the OPA input carries no {@code role_definition}, so a policy falls
 * back to deciding on the subject's roles alone. It lets the client and the authorization manager
 * work before an application provides real role definitions; an app overrides it with one bean.
 *
 * <p>This is an in-process, deterministic supplier with no remote source to be unavailable, so it
 * never throws {@link RoleResolutionException} — it always returns the authoritative no-role signal.
 */
public final class NoOpRoleDefinitionSupplier implements RoleDefinitionSupplier {

    @Override
    public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
        return Optional.empty();
    }
}
