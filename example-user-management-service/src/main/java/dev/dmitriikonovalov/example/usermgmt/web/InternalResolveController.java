package dev.dmitriikonovalov.example.usermgmt.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.service.EffectiveRoleService;
import dev.dmitriikonovalov.example.usermgmt.service.TagDefinitionService;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The <b>internal</b> attribute-source API the catalog calls in-network (never gateway-fronted). Two
 * contracts:
 *
 * <ol>
 *   <li>{@code GET /internal/effective-role} — the caller's effective {@code core.RoleDefinition} on the
 *       team whose team-target governs a resource (consumed by {@code HttpRoleDefinitionSupplier}); a
 *       no-match is {@code 204} so the catalog supplier maps it to empty and the policy default-denies;</li>
 *   <li>{@code GET /internal/effective-roles} — the <em>batch</em> form (Slice 7.3, ADR 0024): N
 *       {@code target=<type>:<id>} params → one {@code 200} array with exactly one entry per target
 *       ({@code role} or explicit {@code null} — never {@code 204}); see the method javadoc;</li>
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
     * The <b>batch</b> resolve (Slice 7.3, ADR 0024): one exchange answering the caller's effective
     * role on N distinct targets — the wire behind pages whose rows have distinct governing roots
     * (consumed by the catalog's {@code HttpRoleDefinitionSupplier.lookupAll}).
     *
     * <p><b>Contract</b> (the client classifies strictly, B2-style):
     * <ul>
     *   <li>{@code 200} with a JSON array carrying <strong>exactly one entry per requested
     *       target</strong> — {@code {resourceType, resourceId, role}} with {@code role} the resolved
     *       definition or an <strong>explicit {@code null}</strong> for the authoritative no-role.
     *       Never {@code 204}: no-role travels in-body, per entry.</li>
     *   <li>{@code 400} for a structurally invalid request: a missing {@code userId}/{@code target},
     *       a malformed target ({@code <type>:<id>} — no colon, empty part, non-UUID id), or a
     *       duplicate target (which would make one-entry-per-target ambiguous). The client treats a
     *       4xx as a <em>permanent</em> outage. A syntactically valid but ungoverned/unknown type is
     *       NOT an error — no team governs it, so its entry is the authoritative {@code role:null}
     *       (team target types have no registry; they are whatever teams govern).</li>
     *   <li>a server-side failure stays {@code 5xx} for the whole request — never a fabricated
     *       partial body (5xx-over-partial, ADR 0024 §2).</li>
     * </ul>
     *
     * <p>Resolution loops {@link EffectiveRoleService#resolveForResource} per target (in-process —
     * the round-trips being coalesced are the HTTP ones, not the queries).
     */
    @GetMapping("/internal/effective-roles")
    public ResponseEntity<List<EffectiveRoleEntry>> effectiveRoles(
            @RequestParam("userId") String userId,
            @RequestParam("target") List<String> targets) {
        // Validate EVERY target before resolving ANY — a malformed request never gets a partial answer.
        Set<ParsedTarget> parsed = new LinkedHashSet<>();
        for (String raw : targets) {
            ParsedTarget target = ParsedTarget.parse(raw);
            if (!parsed.add(target)) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "duplicate target: " + raw);
            }
        }
        List<EffectiveRoleEntry> entries = new ArrayList<>(parsed.size());
        for (ParsedTarget target : parsed) {
            RoleDefinition role = effectiveRoles
                    .resolveForResource(userId, target.resourceType(), target.resourceId())
                    .orElse(null);
            entries.add(new EffectiveRoleEntry(target.resourceType(), target.resourceId(), role));
        }
        return ResponseEntity.ok(entries);
    }

    /** One batch entry; {@code role} is serialized as an explicit {@code null} for no-role (ALWAYS
     * inclusion pinned here so an app-wide NON_NULL default could never bend the wire contract). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record EffectiveRoleEntry(String resourceType, UUID resourceId, RoleDefinition role) {}

    /** A validated {@code <type>:<id>} target; any structural defect is a 400. */
    private record ParsedTarget(String resourceType, UUID resourceId) {

        static ParsedTarget parse(String raw) {
            int colon = raw.indexOf(':');
            if (colon <= 0 || colon == raw.length() - 1) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "malformed target (want <type>:<id>): " + raw);
            }
            String type = raw.substring(0, colon);
            String id = raw.substring(colon + 1);
            try {
                return new ParsedTarget(type, UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "malformed target id (want a UUID): " + raw);
            }
        }
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
