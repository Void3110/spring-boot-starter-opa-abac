package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.service.EffectiveRoleService;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The <b>internal</b> effective-role resolve API — the contract the catalog's
 * {@code HttpRoleDefinitionSupplier} consumes (a single-bean swap for the demo supplier). It walks the
 * caller's team memberships server-side and returns the role definition bound on the team whose
 * team-target matches the resource.
 *
 * <p><b>Internal-only.</b> Mounted under {@code /internal/**} (permitted in {@code SecurityConfig}); it
 * is an in-network attribute source the catalog calls, never exposed through the gateway. Returns the
 * raw {@code core.RoleDefinition} wire shape ({@code code}/{@code attributes}/{@code permissions}) so
 * the catalog deserializes it directly. A no-match is {@code 204 No Content} (empty), <em>not</em> an
 * error — the catalog supplier maps that to {@code Optional.empty()} and the policy default-denies
 * (fail-closed). It is hand-written (not OpenAPI-generated) because it is an internal contract, not
 * part of the public management API.
 */
@RestController
public class InternalResolveController {

    private final EffectiveRoleService effectiveRoles;

    public InternalResolveController(EffectiveRoleService effectiveRoles) {
        this.effectiveRoles = effectiveRoles;
    }

    @GetMapping("/internal/effective-role")
    public ResponseEntity<RoleDefinition> effectiveRole(
            @RequestParam("userId") String userId,
            @RequestParam("resourceType") String resourceType,
            @RequestParam("resourceId") UUID resourceId) {
        return effectiveRoles.resolveForResource(userId, resourceType, resourceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
