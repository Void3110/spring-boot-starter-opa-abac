package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.RoleDefinitionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A small <b>internal</b> bootstrap endpoint used only by the local e2e rig to seed deterministic demo
 * data whose keys (the IdP {@code sub}, the team-target catalog id) are only known at run time — i.e.
 * after tokens are minted. Mounted under {@code /internal/**} (permitted in {@code SecurityConfig}, an
 * in-network surface), so it is never exposed through the gateway and is not part of the public,
 * {@code @OpaPreAuthorize}-secured management API. The management API stays the demonstrated, secured
 * path; this is test scaffolding for the matrix, kept deliberately minimal and idempotent.
 */
@RestController
public class InternalBootstrapController {

    private final UserRepository users;
    private final TeamRepository teams;
    private final TeamMembershipRepository memberships;
    private final RoleDefinitionRepository roles;
    private final RoleDefinitionService roleDefinitions;

    public InternalBootstrapController(
            UserRepository users,
            TeamRepository teams,
            TeamMembershipRepository memberships,
            RoleDefinitionRepository roles,
            RoleDefinitionService roleDefinitions) {
        this.users = users;
        this.teams = teams;
        this.memberships = memberships;
        this.roles = roles;
        this.roleDefinitions = roleDefinitions;
    }

    /** Idempotently ensure a user with the given IdP subject exists; returns its id. */
    @PostMapping("/internal/bootstrap/users")
    @Transactional
    public ResponseEntity<Map<String, UUID>> ensureUser(@RequestBody EnsureUser body) {
        UUID id = users.findBySubject(body.subject())
                .orElseGet(() -> users.save(
                        new User(UUID.randomUUID(), body.subject(), body.displayName())))
                .getId();
        return ResponseEntity.ok(Map.of("userId", id));
    }

    /** Idempotently ensure a team for a team-target exists; returns its id. */
    @PostMapping("/internal/bootstrap/teams")
    @Transactional
    public ResponseEntity<Map<String, UUID>> ensureTeam(@RequestBody EnsureTeam body) {
        UUID id = teams.findByTargetTypeAndTargetId(body.targetType(), body.targetId())
                .orElseGet(() -> teams.save(
                        new Team(UUID.randomUUID(), body.name(), body.targetType(), body.targetId())))
                .getId();
        return ResponseEntity.ok(Map.of("teamId", id));
    }

    /**
     * Idempotently ensure a team-scoped custom role exists; returns its id. {@code permissions} uses
     * the {@code {resourceType: [category tokens]}} shape (Phase 6.5) with a required {@code roleLevel}
     * and optional {@code deniedActions}/{@code requiredTags}/{@code matchMode}. Routes through
     * {@link RoleDefinitionService} so the authoring contract applies here too — the bootstrap is
     * <b>not</b> a validation bypass (an invalid payload answers 422 exactly like the management API).
     */
    @PostMapping("/internal/bootstrap/custom-roles")
    @Transactional
    public ResponseEntity<Map<String, UUID>> ensureCustomRole(@RequestBody EnsureCustomRole body) {
        boolean exists = roles.findByTeamIdAndCode(body.teamId(), body.code()).isPresent();
        RoleDefinitionEntity role = exists
                ? roleDefinitions.update(
                        body.teamId(), body.code(), body.roleLevel(), Map.of(),
                        body.permissions(), body.deniedActions(), body.requiredTags(), body.matchMode())
                : roleDefinitions.create(
                        body.teamId(), body.code(), body.roleLevel(), Map.of(),
                        body.permissions(), body.deniedActions(), body.requiredTags(), body.matchMode());
        return ResponseEntity.ok(Map.of("roleId", role.getId()));
    }

    /** Idempotently ensure a membership (by team+user) bound to a role code (system or this team's custom). */
    @PostMapping("/internal/bootstrap/memberships")
    @Transactional
    public ResponseEntity<Map<String, UUID>> ensureMembership(@RequestBody EnsureMembership body) {
        RoleDefinitionEntity role = roles.findBySystemTrueAndCode(body.roleCode())
                .or(() -> roles.findByTeamIdAndCode(body.teamId(), body.roleCode()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + body.roleCode()));
        TeamMembership membership = memberships.findByTeamIdAndUserId(body.teamId(), body.userId())
                .map(m -> {
                    m.setRoleDefinitionId(role.getId());
                    return memberships.save(m);
                })
                .orElseGet(() -> memberships.save(new TeamMembership(
                        UUID.randomUUID(), body.teamId(), body.userId(), role.getId())));
        return ResponseEntity.ok(Map.of("membershipId", membership.getId()));
    }

    public record EnsureUser(String subject, String displayName) {
    }

    public record EnsureTeam(String name, String targetType, UUID targetId) {
    }

    public record EnsureCustomRole(
            UUID teamId,
            String code,
            Integer roleLevel,
            Map<String, List<String>> permissions,
            Map<String, List<String>> deniedActions,
            Map<String, List<String>> requiredTags,
            String matchMode) {
    }

    public record EnsureMembership(UUID teamId, UUID userId, String roleCode) {
    }
}
