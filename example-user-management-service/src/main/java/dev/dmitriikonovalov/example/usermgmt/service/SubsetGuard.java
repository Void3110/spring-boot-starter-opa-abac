package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The single enforcement point for the no-self-escalation <b>subset rule</b>, shared by
 * {@link MembershipService} (assigning a role) and {@link RoleDefinitionService} (defining one). Both
 * the resolution of the actor's own permissions and the {@link PermissionSubset} check live here, so
 * the rule has exactly one implementation.
 */
@Component
public class SubsetGuard {

    private final EffectiveRoleService effectiveRoles;

    public SubsetGuard(EffectiveRoleService effectiveRoles) {
        this.effectiveRoles = effectiveRoles;
    }

    /**
     * Reject {@code candidate} unless it is a subset of the actor's own effective permissions on the
     * team. An actor with no membership holds nothing and so can grant nothing.
     *
     * @throws SubsetRuleViolationException if the actor is not a member, or the candidate exceeds them
     */
    public void requireWithinActorPermissions(
            UUID actorUserId, UUID teamId, Map<String, List<String>> candidate) {
        Map<String, List<String>> actorPerms = effectiveRoles.membership(teamId, actorUserId)
                .map(effectiveRoles::roleOf)
                .map(RoleDefinitionEntity::getPermissions)
                .orElseThrow(() -> new SubsetRuleViolationException(
                        "Actor has no membership on team " + teamId + " and cannot grant roles"));
        if (!PermissionSubset.isSubset(candidate, actorPerms)) {
            throw new SubsetRuleViolationException(
                    "The role exceeds the actor's own permissions (subset rule)");
        }
    }
}
