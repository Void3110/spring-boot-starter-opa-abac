package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.service.EffectiveRoleService;
import dev.dmitriikonovalov.example.usermgmt.service.TagDefinitionService;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The <b>internal</b> attribute-source API the catalog calls in-network (never gateway-fronted). Two
 * contracts:
 *
 * <ol>
 *   <li>{@code GET /internal/effective-role} — the caller's effective {@code core.RoleDefinition} on the
 *       team whose team-target governs a resource (consumed by {@code HttpRoleDefinitionSupplier}); a
 *       no-match is {@code 204} so the catalog supplier maps it to empty and the policy default-denies;</li>
 *   <li>{@code GET /internal/tag-definitions} — the dictionary <em>applicable to a resource</em> (global
 *       keys + the governing team's keys), consumed by the catalog's {@code TagDefinitionClient} to
 *       validate assigned tags. The catalog fails the write closed if this fetch fails.</li>
 * </ol>
 *
 * <p><b>Internal-only.</b> Mounted under {@code /internal/**} (permitted in {@code SecurityConfig}); these
 * are in-network attribute sources, never exposed through the gateway. Hand-written (not
 * OpenAPI-generated) because they are internal contracts, not part of the public management API.
 */
@RestController
public class InternalResolveController {

    private final EffectiveRoleService effectiveRoles;
    private final TagDefinitionService tagDefinitions;

    public InternalResolveController(
            EffectiveRoleService effectiveRoles, TagDefinitionService tagDefinitions) {
        this.effectiveRoles = effectiveRoles;
        this.tagDefinitions = tagDefinitions;
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

    /**
     * The <b>governed target ids</b> of a type the subject governs through team membership — the data
     * source for the catalog list's base scope (Slice B4, consumed by {@code HttpGovernedScopeResolver}).
     * Always a {@code 200} with a JSON array; an <b>empty array</b> (never {@code 204}) is the
     * authoritative "governs nothing" for an unknown subject or a subject on no team of that type — the
     * resolver fails closed on an empty array exactly as it would on an error. Distinct ids.
     */
    @GetMapping("/internal/governed-targets")
    public ResponseEntity<List<UUID>> governedTargets(
            @RequestParam("subject") String subject,
            @RequestParam("resourceType") String resourceType) {
        return ResponseEntity.ok(effectiveRoles.governedTargets(subject, resourceType));
    }

    /**
     * The dictionary applicable to a resource — the globals plus the governing team's keys. Always a
     * {@code 200} with a (possibly globals-only) list; the catalog validates against it and rejects an
     * illegal value (422). A fetch <em>failure</em> on the catalog side fails the write closed.
     */
    @GetMapping("/internal/tag-definitions")
    public ResponseEntity<List<TagDefinition>> tagDefinitionsForResource(
            @RequestParam("resourceType") String resourceType,
            @RequestParam("resourceId") UUID resourceId) {
        var result = tagDefinitions.applicableForResource(resourceType, resourceId).stream()
                .map(UserMgmtMapper::toDto)
                .toList();
        return ResponseEntity.ok(result);
    }
}
