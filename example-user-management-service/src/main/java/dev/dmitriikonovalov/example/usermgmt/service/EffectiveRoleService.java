package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private final TeamMembershipRepository memberships;
    private final RoleDefinitionRepository roles;

    public EffectiveRoleService(
            TeamMembershipRepository memberships, RoleDefinitionRepository roles) {
        this.memberships = memberships;
        this.roles = roles;
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
     * The caller's <b>resource</b> role for a team-target — the bound role's stored permissions, with
     * the wildcard {@code "*"} expanded to {@code targetType} so the catalog policy can read
     * {@code permissions[targetType]}.
     */
    public RoleDefinition resourceRole(TeamMembership membership, String targetType) {
        RoleDefinitionEntity role = roleOf(membership);
        return new RoleDefinition(
                role.getCode(), role.getAttributes(), expandWildcard(role.getPermissions(), targetType));
    }

    private static Map<String, List<String>> expandWildcard(
            Map<String, List<String>> permissions, String targetType) {
        if (permissions.containsKey("*")) {
            return Map.of(targetType, permissions.get("*"));
        }
        return permissions;
    }
}
