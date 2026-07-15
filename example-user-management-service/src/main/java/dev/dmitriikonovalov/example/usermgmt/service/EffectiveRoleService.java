package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.TagMatchMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a user's <b>effective role</b> on a team from live membership — the single source of truth
 * (membership; no denormalized grants). Always re-derives, so removing a membership revokes access
 * immediately. Shared by:
 *
 * <ul>
 *   <li>the user-service's own {@code RoleDefinitionSupplier} (ticket 4) — the management projection
 *       on resource type {@code "team"};</li>
 *   <li>the effective-role resolve API (ticket 7) — the resource projection on the team-target type.</li>
 * </ul>
 *
 * <p>Two distinct {@link RoleDefinition} projections of the same membership:
 * <ul>
 *   <li>{@link #managementRole(UUID, UUID)} → {@code permissions["team"]} = the capability ladder
 *       ({@link TeamRoleCapabilities}); what the dogfooded {@code @OpaPreAuthorize} decides on;</li>
 *   <li>{@link #resourceRole(TeamMembership, String)} → the role's stored {@code permissions} with the
 *       wildcard {@code "*"} expanded to the concrete team-target type; what the catalog consumes.</li>
 * </ul>
 */
@Service
public class EffectiveRoleService {

    private static final Logger log = LoggerFactory.getLogger(EffectiveRoleService.class);

    private final TeamMembershipRepository memberships;
    private final RoleDefinitionRepository roles;
    private final TeamRepository teams;
    private final UserRepository users;
    private final TeamTargetMatcher targetMatcher;

    public EffectiveRoleService(
            TeamMembershipRepository memberships,
            RoleDefinitionRepository roles,
            TeamRepository teams,
            UserRepository users,
            TeamTargetMatcher targetMatcher) {
        this.memberships = memberships;
        this.roles = roles;
        this.teams = teams;
        this.users = users;
        this.targetMatcher = targetMatcher;
    }

    /** The caller's membership on a team, if any (the binding everything re-derives from). */
    @Transactional(readOnly = true)
    public Optional<TeamMembership> membership(UUID teamId, UUID userId) {
        return memberships.findByTeamIdAndUserId(teamId, userId);
    }

    /** The bound role entity for a membership. */
    public RoleDefinitionEntity roleOf(TeamMembership membership) {
        return roles.findById(membership.getRoleDefinitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Membership " + membership.getId() + " points at a missing role definition"));
    }

    /**
     * The caller's <b>management</b> role on a team — {@code permissions["team"]} set to the capability
     * ladder for the bound role's code. Empty when the user has no membership on the team (→ the
     * dogfooded policy default-denies).
     */
    @Transactional(readOnly = true)
    public Optional<RoleDefinition> managementRole(UUID teamId, UUID userId) {
        return membership(teamId, userId).map(m -> {
            RoleDefinitionEntity role = roleOf(m);
            return new RoleDefinition(
                    role.getCode(),
                    role.getAttributes(),
                    Map.of("team", TeamRoleCapabilities.forCode(role.getCode())));
        });
    }

    /**
     * Resolve the <b>effective resource role</b> a subject holds on a specific resource — the contract
     * the catalog's {@code HttpRoleDefinitionSupplier} consumes. Walks:
     * {@code subject → user → memberships → team matched by the TeamTargetMatcher → bound role}, and
     * returns that role as a {@code core.RoleDefinition} (with {@code "*"} expanded to the resource
     * type). Empty — never an error — when the subject maps to no user, or no membership's team governs
     * the resource (so the catalog policy default-denies). Always re-derived from live membership, so a
     * removed member resolves empty (revocation propagates).
     *
     * @param subject the IdP subject ({@code sub}) the catalog forwards (not the internal user id)
     */
    @Transactional(readOnly = true)
    public Optional<RoleDefinition> resolveForResource(
            String subject, String resourceType, UUID resourceId) {
        Optional<User> user = users.findBySubject(subject);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        for (TeamMembership m : memberships.findByUserId(user.get().getId())) {
            Optional<Team> team = teams.findById(m.getTeamId());
            if (team.isPresent() && targetMatcher.matches(team.get(), resourceType, resourceId)) {
                return Optional.of(resourceRole(m, resourceType));
            }
        }
        return Optional.empty();
    }

    /**
     * The <b>governed target ids</b> of {@code resourceType} a subject governs through team membership —
     * the data source for the catalog list's base scope (Slice B4, ADR 0018). Walks the same join as
     * {@link #resolveForResource} but collects ALL matches instead of stopping at the first: {@code subject
     * → user → memberships → teams WHERE target_type = resourceType → distinct target_id}.
     *
     * <p>Returns an <b>empty list</b> — never an error — when the subject maps to no user or governs no
     * team of that type; the caller ({@code HttpGovernedScopeResolver}) treats an empty list as
     * "governs nothing" and fails closed to an empty page. Always re-derived from live membership, so a
     * removed member's catalog disappears from their list immediately (revocation propagates).
     *
     * <p><b>Distinct</b> by id: two memberships of the same subject on the same governing team (or two
     * teams governing the same target) contribute the id once.
     *
     * @param subject      the IdP subject ({@code sub}) the catalog forwards (not the internal user id)
     * @param resourceType the team-target type to collect (e.g. {@code "catalog"})
     * @return the distinct governed target ids; empty when none
     */
    @Transactional(readOnly = true)
    public List<UUID> governedTargets(String subject, String resourceType) {
        Optional<User> user = users.findBySubject(subject);
        if (user.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> targetIds = new LinkedHashSet<>(); // insertion-ordered + de-duplicated
        for (TeamMembership m : memberships.findByUserId(user.get().getId())) {
            teams.findById(m.getTeamId())
                    .filter(t -> t.getTargetType().equals(resourceType))
                    .ifPresent(t -> targetIds.add(t.getTargetId()));
        }
        return List.copyOf(targetIds);
    }

    /**
     * The caller's <b>resource</b> role for a team-target — the bound role's stored permissions, with
     * the wildcard {@code "*"} expanded to {@code targetType} so the catalog policy can read
     * {@code permissions[targetType]}. Wildcard expansion applies to {@code denied_actions} exactly
     * as to the grants (Phase 6.5) — a {@code "*"}-scoped denial must narrow the resolved role, or
     * the wire role would read WIDER than the stored one.
     */
    public RoleDefinition resourceRole(TeamMembership membership, String targetType) {
        RoleDefinitionEntity role = roleOf(membership);
        return new RoleDefinition(
                role.getCode(),
                role.getAttributes(),
                expandWildcard(role.getPermissions(), targetType),
                expandWildcard(role.getDeniedActions(), targetType),
                role.getRequiredTags(),
                parseMatchMode(role.getMatchMode()));
    }

    /**
     * A stored {@code match_mode} string → the enum; null/blank → null (no requirement / default).
     * An UNKNOWN non-blank value maps to {@link TagMatchMode#ALL_OF} — the narrower mode — never to
     * null: null would let {@code core.RoleDefinition} default a present tag requirement to the wider
     * {@code ANY_OF}, silently widening access on a corrupted row (fail-open). Narrowing the mode keeps
     * the resolved role in play (with its {@code denied_actions}/{@code required_tags}); failing the
     * resolution entirely would drop that narrowing — and post-B4 (ADR 0018) "no role definition" denies
     * the instance outright (no blanket realm-role fallback), so it is a worse signal, not a safer one.
     */
    private static TagMatchMode parseMatchMode(String matchMode) {
        if (matchMode == null || matchMode.isBlank()) {
            return null;
        }
        try {
            return TagMatchMode.valueOf(matchMode);
        } catch (IllegalArgumentException _) {
            log.warn("Unknown stored match_mode '{}' — narrowing to ALL_OF (fail-closed)", matchMode);
            return TagMatchMode.ALL_OF;
        }
    }

    /**
     * Expand a {@code "*"} wildcard key to the concrete {@code targetType}. A present concrete key
     * WINS over the wildcard — the same shadowing as the policy-side {@code permissions.tokens_for}
     * (one seam, one semantic), so the resolve wire and raw-row evaluation can never diverge on a
     * mixed map.
     */
    private static Map<String, List<String>> expandWildcard(
            Map<String, List<String>> map, String targetType) {
        if (map.containsKey("*") && !map.containsKey(targetType)) {
            return Map.of(targetType, map.get("*"));
        }
        return map;
    }
}
