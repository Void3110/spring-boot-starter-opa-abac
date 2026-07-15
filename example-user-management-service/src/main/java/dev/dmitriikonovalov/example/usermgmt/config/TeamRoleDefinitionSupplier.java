package dev.dmitriikonovalov.example.usermgmt.config;

import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.EffectiveRoleService;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
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
 *
 * <p><b>Outage vs no-role (B2).</b> This supplier is in-process (its source is the local database), but
 * the database can still be <em>unavailable</em>. A {@link org.springframework.dao.DataAccessException}
 * from the repository/service is a role-source <strong>outage</strong>, so it is mapped to
 * {@link RoleResolutionException} (the tri-state contract) rather than swallowed to {@code empty} — the
 * caller then fails closed (the gate denies) instead of the empty signal. The outcome is unchanged
 * (user-management has no realm fallback, so a DB error already denied via the gate's broad catch); B2
 * only makes the outage <em>legible</em> and contract-conformant. The authoritative no-role cases above
 * stay {@code Optional.empty()}.
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
            return Optional.empty(); // not a team / no id → authoritative no-role
        }
        UUID teamId = parseUuid(resourceId);
        if (teamId == null) {
            return Optional.empty(); // unparseable id → authoritative no-role
        }
        try {
            // No user / not a member → Optional.empty() (authoritative no-role). A data-access failure is
            // an OUTAGE → throw so the caller fails closed (B2), never an empty that could be misread.
            return users.findBySubject(userId)
                    .flatMap(user -> effectiveRoles.managementRole(teamId, user.getId()));
        } catch (DataAccessException e) {
            throw new RoleResolutionException("team role source unavailable", e);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
