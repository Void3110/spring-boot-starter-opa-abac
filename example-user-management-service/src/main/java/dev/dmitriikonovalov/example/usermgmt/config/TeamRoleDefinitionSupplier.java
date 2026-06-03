package dev.dmitriikonovalov.example.usermgmt.config;

import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.EffectiveRoleService;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The user-service <b>dogfoods</b> the starter: its own {@link RoleDefinitionSupplier} resolves the
 * caller's role <em>on the team being managed</em>, so the same {@code @OpaPreAuthorize} mechanism the
 * service produces role definitions for also guards its management API.
 *
 * <p>Given the {@code @OpaPreAuthorize} lookup {@code (subjectId, "team", teamId)}: map the subject to
 * a {@code User}, then return that user's <em>management</em> role on the team (the capability ladder,
 * {@code permissions["team"]}). Returns empty — so the policy default-denies — when the subject maps
 * to no user, the resource is not a team, the id is absent/unparseable, or the user is not a member
 * (the confused-deputy and fail-closed guards both hold here).
 */
@Component
public class TeamRoleDefinitionSupplier implements RoleDefinitionSupplier {

    private final UserRepository users;
    private final EffectiveRoleService effectiveRoles;

    public TeamRoleDefinitionSupplier(UserRepository users, EffectiveRoleService effectiveRoles) {
        this.users = users;
        this.effectiveRoles = effectiveRoles;
    }

    @Override
    public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
        if (!"team".equals(resourceType) || resourceId == null) {
            return Optional.empty();
        }
        UUID teamId = parseUuid(resourceId);
        if (teamId == null) {
            return Optional.empty();
        }
        return users.findBySubject(userId)
                .flatMap(user -> effectiveRoles.managementRole(teamId, user.getId()));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
