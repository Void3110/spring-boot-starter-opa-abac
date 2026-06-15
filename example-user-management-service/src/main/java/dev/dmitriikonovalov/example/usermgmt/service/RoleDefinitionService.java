package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team-scoped custom role-definition management (ticket 5). An owner defines roles scoped to one team,
 * within the Phase-6.5 authoring contract. {@code @Transactional}; controllers stay thin.
 *
 * <ul>
 *   <li><b>System roles are immutable</b> — update/delete of a {@code system=true} role is rejected
 *       (the global ladder is stable contract).</li>
 *   <li><b>The authoring contract (ADR 0007)</b> — a role is authored by picking a {@code roleLevel}
 *       from the authorable ladder; only category tokens within the level's ceiling pass; denials must
 *       strictly subtract from the granted expansion; the explicit level is the single source of
 *       {@code attributes.role_level}. Violations → 422 {@code ROLE_DEFINITION_INVALID}.</li>
 * </ul>
 *
 * <p>The pre-6.5 authoring-time subset-of-own check is gone — vestigial under owner-only authoring;
 * the level ceiling is the real bound (00-DESIGN §2.8). Authorization of <em>who</em> may define roles
 * stays the {@code @OpaPreAuthorize(action="team:define-roles")} decision on the controller — owner
 * only (only the owner's management ladder carries the {@code define-roles} verb).
 */
@Service
public class RoleDefinitionService {

    /**
     * Categories that confer team-management power if carried on a custom role under a {@code "team"}
     * key (Phase 6.7): {@code CONTROL} (the membership verbs) and {@code TAG} (which would grant team
     * {@code define-tags}). {@code CONTROL} is additionally rejected under <em>any</em> key.
     */
    private static final Set<String> TEAM_MANAGEMENT_CATEGORIES = Set.of("CONTROL", "TAG");

    private final RoleDefinitionRepository roles;
    private final TeamRepository teams;

    public RoleDefinitionService(RoleDefinitionRepository roles, TeamRepository teams) {
        this.roles = roles;
        this.teams = teams;
    }

    /** System roles plus this team's custom roles — the team's full role list, paged (5.95). */
    @Transactional(readOnly = true)
    public Page<RoleDefinitionEntity> list(UUID teamId, Pageable pageable) {
        requireTeam(teamId);
        return roles.findBySystemTrueOrTeamId(teamId, pageable);
    }

    /** Create a team-scoped custom role ({@code system=false}, {@code teamId} set), contract-validated. */
    @Transactional
    public RoleDefinitionEntity create(
            UUID teamId,
            String code,
            Integer roleLevel,
            Map<String, Object> attributes,
            Map<String, List<String>> permissions,
            Map<String, List<String>> deniedActions,
            Map<String, List<String>> requiredTags,
            String matchMode) {
        lockTeam(teamId);
        if (roles.findBySystemTrueAndCode(code).isPresent()) {
            throw new RoleConflictException("'" + code + "' is a reserved system role code");
        }
        if (roles.findByTeamIdAndCode(teamId, code).isPresent()) {
            throw new RoleConflictException(
                    "A custom role '" + code + "' already exists on team " + teamId);
        }
        permissions = permissions == null ? Map.of() : permissions;
        deniedActions = deniedActions == null ? Map.of() : deniedActions;
        validateContract(roleLevel, permissions, deniedActions);
        RoleDefinitionEntity role = new RoleDefinitionEntity(
                UUID.randomUUID(), code, false, teamId,
                withRoleLevel(attributes, roleLevel), permissions,
                requiredTags, normalizeMatchMode(requiredTags, matchMode));
        role.setDeniedActions(deniedActions);
        return roles.save(role);
    }

    /** Update a team-scoped custom role, contract-validated. System roles are immutable. */
    @Transactional
    public RoleDefinitionEntity update(
            UUID teamId,
            String code,
            Integer roleLevel,
            Map<String, Object> attributes,
            Map<String, List<String>> permissions,
            Map<String, List<String>> deniedActions,
            Map<String, List<String>> requiredTags,
            String matchMode) {
        lockTeam(teamId);
        RoleDefinitionEntity role = requireCustomRole(teamId, code);
        permissions = permissions == null ? Map.of() : permissions;
        deniedActions = deniedActions == null ? Map.of() : deniedActions;
        validateContract(roleLevel, permissions, deniedActions);
        role.setAttributes(withRoleLevel(attributes, roleLevel));
        role.setPermissions(permissions);
        role.setDeniedActions(deniedActions);
        role.setRequiredTags(requiredTags);
        role.setMatchMode(normalizeMatchMode(requiredTags, matchMode));
        return roles.save(role);
    }

    /**
     * The custom-role authoring contract (Phase 6.5, extended in Phase 6.7; each violation → 422
     * {@code ROLE_DEFINITION_INVALID}):
     * <ol>
     *   <li>{@code roleLevel} required, one of the authorable ladder ({@code 10/20/25/30});</li>
     *   <li><b>no team-management power (Phase 6.7)</b> — a custom role may not carry a control-plane
     *       category ({@code CONTROL}) anywhere, nor a team-management token under a {@code "team"} key.
     *       Management capability is fixed to the system-role ladder ({@link TeamRoleCapabilities}),
     *       which {@code managementRole} projects from the role <em>code</em>; a custom role's stored
     *       {@code "team"} tokens are ignored by the projection ("ceiling ≠ capability"), so carrying
     *       them was silent dead data — now an honest error;</li>
     *   <li>every (catalog-plane) permission token is one of the four authorable <b>categories</b>
     *       (flat verbs and fine actions are retired at the API boundary);</li>
     *   <li>granted categories stay within the level's ceiling ({@code GRANT} only at 30);</li>
     *   <li><b>strict denial validation</b> — per type, denied fine actions must subtract from the
     *       expansion of what that type actually grants (wildcard-aware, mirroring the policy's
     *       lookup): denying the never-granted is rejected, not silently inert.</li>
     * </ol>
     * Package-private for the unit suite (U4–U8); the API ITs prove the 422 wire contract (I5).
     */
    static void validateContract(
            Integer roleLevel,
            Map<String, List<String>> permissions,
            Map<String, List<String>> deniedActions) {
        if (roleLevel == null || !PermissionCategories.AUTHORABLE_LEVELS.contains(roleLevel)) {
            throw new RoleDefinitionInvalidException(
                    "roleLevel must be one of 10 (reader), 20 (member), 25 (senior), 30 (administrator)");
        }
        rejectTeamManagementTokens(permissions);
        var ceiling = PermissionCategories.ceiling(roleLevel);
        for (var entry : permissions.entrySet()) {
            for (String token : nullSafe(entry.getValue())) {
                if (!PermissionCategories.AUTHORABLE_CATEGORIES.contains(token)) {
                    throw new RoleDefinitionInvalidException(
                            "'" + token + "' is not an authorable permission category (READ/WRITE/TAG/GRANT)");
                }
                if (!ceiling.contains(token)) {
                    throw new RoleDefinitionInvalidException(
                            "category '" + token + "' exceeds the level-" + roleLevel + " ceiling");
                }
            }
        }
        for (var entry : deniedActions.entrySet()) {
            var granted = PermissionCategories.expand(grantedTokensFor(permissions, entry.getKey()));
            for (String action : nullSafe(entry.getValue())) {
                if (!granted.contains(action)) {
                    throw new RoleDefinitionInvalidException(
                            "denied action '" + action + "' is not granted for type '"
                                    + entry.getKey() + "' (denials must subtract from grants)");
                }
            }
        }
    }

    /**
     * Phase 6.7 — a custom role may not smuggle control-plane power. Reject (422):
     * <ul>
     *   <li>{@code CONTROL} under <em>any</em> key — it is the control-plane category, never authorable
     *       on a custom role; and</li>
     *   <li>a team-management category ({@code CONTROL} or {@code TAG}) under a {@code "team"} key —
     *       {@code TAG} there would grant {@code define-tags}, a management verb.</li>
     * </ul>
     * Management capability is fixed to the system-role ladder ({@link TeamRoleCapabilities}), which
     * {@code managementRole} projects from the role <em>code</em> and which ignores a custom role's
     * stored {@code "team"} tokens entirely ("ceiling ≠ capability"). Carrying them was silent dead
     * data; this turns the footgun into an honest error and breaks no working flow. {@code "team"}
     * resource permissions belong on the team-<em>target</em> type, not under a {@code "team"} key.
     */
    private static void rejectTeamManagementTokens(Map<String, List<String>> permissions) {
        for (var entry : permissions.entrySet()) {
            boolean teamKey = "team".equals(entry.getKey());
            for (String token : nullSafe(entry.getValue())) {
                boolean controlAnywhere = PermissionCategories.CONTROL_PLANE_CATEGORIES.contains(token);
                boolean managementUnderTeam = teamKey && TEAM_MANAGEMENT_CATEGORIES.contains(token);
                if (controlAnywhere || managementUnderTeam) {
                    throw new RoleDefinitionInvalidException(
                            "custom roles cannot carry team-management categories (token '" + token
                                    + "' under key '" + entry.getKey()
                                    + "') — management capability is fixed to the system-role ladder; "
                                    + "put the team's resource permissions on the team-target type");
                }
            }
        }
    }

    /** The granted tokens for a type — the concrete key wins, the {@code "*"} wildcard backs it up. */
    private static List<String> grantedTokensFor(Map<String, List<String>> permissions, String type) {
        List<String> tokens = permissions.get(type);
        if (tokens == null) {
            tokens = permissions.get("*");
        }
        return nullSafe(tokens);
    }

    private static List<String> nullSafe(List<String> list) {
        return list == null ? List.of() : list;
    }

    /**
     * The explicit {@code roleLevel} is the single source of {@code attributes.role_level} — an
     * attributes-supplied value is overwritten (documented in the OpenAPI description).
     */
    private static Map<String, Object> withRoleLevel(Map<String, Object> attributes, Integer roleLevel) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(
                attributes == null ? Map.of() : attributes);
        merged.put("role_level", roleLevel);
        return Map.copyOf(merged);
    }

    /**
     * {@code match_mode} is meaningful only with a tag requirement: default it to {@code ANY_OF} when
     * required tags are present and none was given; clear it when there is no requirement. This mirrors
     * the {@code core.RoleDefinition} normalization so the stored row and the resolved role agree.
     */
    private static String normalizeMatchMode(
            Map<String, List<String>> requiredTags, String matchMode) {
        boolean hasRequirement = requiredTags != null && !requiredTags.isEmpty();
        if (!hasRequirement) {
            return null;
        }
        return matchMode == null || matchMode.isBlank() ? "ANY_OF" : matchMode;
    }

    /** Delete a team-scoped custom role. System roles are immutable. */
    @Transactional
    public void delete(UUID teamId, String code) {
        lockTeam(teamId);
        RoleDefinitionEntity role = requireCustomRole(teamId, code);
        roles.delete(role);
        // Flush HERE so deleting an in-use role (the team_membership FK) raises a translated
        // DataIntegrityViolationException inside the method — the starter advice maps it to 409.
        // Deferred to commit, the violation can surface as an untranslated wrapper → 500.
        roles.flush();
    }

    private void requireTeam(UUID teamId) {
        if (!teams.existsById(teamId)) {
            throw new IllegalArgumentException("Team not found: " + teamId);
        }
    }

    /**
     * Lock the team row {@code FOR UPDATE} for the rest of the transaction — role definitions feed the
     * subset/ceiling decisions, so their writes serialize with every other team-scoped grant mutation
     * (see {@code MembershipService}'s class doc). Doubles as the existence check.
     */
    private void lockTeam(UUID teamId) {
        teams.findByIdForUpdate(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    /**
     * A custom role of this team by code. A system role (same code) is rejected as immutable; a missing
     * role is a 404.
     */
    private RoleDefinitionEntity requireCustomRole(UUID teamId, String code) {
        if (roles.findBySystemTrueAndCode(code).isPresent()) {
            throw new SystemRoleImmutableException(code);
        }
        return roles.findByTeamIdAndCode(teamId, code)
                .orElseThrow(() -> new RoleNotFoundException(teamId, code));
    }
}
