package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
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

    // Phase 6.5: the authoring-time requireWithinActorPermissions check was removed — vestigial under
    // owner-only authoring; the level ceiling (RoleDefinitionService.validateContract) is the real
    // bound (00-DESIGN §2.8). The assignment-time check below is replaced by the hybrid gates in T5.

    /**
     * The subset rule for <em>assigning an existing role</em> (add member / change role): the candidate
     * role must not exceed the actor in EITHER dimension — its resource permissions (as above) AND its
     * management-capability ladder ({@link TeamRoleCapabilities}, keyed on the role <em>code</em>).
     * Comparing only the resource permissions is the escalation hole the 2026-06-12 retro-audit found:
     * owner and administrator carry identical resource permissions, so an administrator could confer
     * the owner code — and with it {@code define-roles} + {@code transfer-ownership} — on themselves.
     *
     * @throws SubsetRuleViolationException if the actor is not a member, or the candidate exceeds them
     */
    public void requireAssignableByActor(UUID actorUserId, UUID teamId, RoleDefinitionEntity candidate) {
        RoleDefinitionEntity actorRole = actorRole(actorUserId, teamId);
        if (!PermissionSubset.isSubset(candidate.getPermissions(), actorRole.getPermissions())) {
            throw new SubsetRuleViolationException(
                    "The role exceeds the actor's own permissions (subset rule)");
        }
        if (!TeamRoleCapabilities.forCode(actorRole.getCode())
                .containsAll(TeamRoleCapabilities.forCode(candidate.getCode()))) {
            throw new SubsetRuleViolationException(
                    "The role's management capabilities exceed the actor's own (subset rule)");
        }
    }

    private RoleDefinitionEntity actorRole(UUID actorUserId, UUID teamId) {
        return effectiveRoles.membership(teamId, actorUserId)
                .map(effectiveRoles::roleOf)
                .orElseThrow(() -> new SubsetRuleViolationException(
                        "Actor has no membership on team " + teamId + " and cannot grant roles"));
    }
}
