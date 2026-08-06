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
import dev.dmitriikonovalov.example.usermgmt.service.SupervisionService;
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
    private final SupervisionService supervision;

    public InternalBootstrapController(
            UserRepository users,
            TeamRepository teams,
            TeamMembershipRepository memberships,
            RoleDefinitionRepository roles,
            RoleDefinitionService roleDefinitions,
            SupervisionService supervision) {
        this.users = users;
        this.teams = teams;
        this.memberships = memberships;
        this.roles = roles;
        this.roleDefinitions = roleDefinitions;
        this.supervision = supervision;
    }

    /**
     * Idempotently ensure a user with the given IdP subject exists; returns its id. An existing
     * subject is an <b>upsert of {@code displayName} only</b> — a re-seed with a changed name
     * converges to the new value on the same row (same id, no duplicate); an identical re-post is
     * a no-op. The subject key, the id, and memberships/roles are never touched here.
     */
    @PostMapping("/internal/bootstrap/users")
    @Transactional
    public ResponseEntity<Map<String, UUID>> ensureUser(@RequestBody EnsureUser body) {
        UUID id = users.findBySubject(body.subject())
                .map(existing -> {
                    if (body.displayName() != null
                            && !body.displayName().equals(existing.getDisplayName())) {
                        existing.setDisplayName(body.displayName());
                        return users.save(existing);
                    }
                    return existing;
                })
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

    /**
     * <b>Declaratively</b> set a manager's reporting edges: the posted {@code reportIds} <em>replace</em>
     * that manager's whole edge set, so an empty list removes them. Unlike the four {@code ensure}-shaped
     * endpoints above this is a replace, not an upsert — deliberately, because the slice's headline
     * <em>liveness</em> proof (E4) needs to <b>remove</b> a report and observe access withdraw on the
     * next request, and every shipped bootstrap endpoint is upsert-only. A declarative set is the
     * narrowest seam that provides removal without adding a delete verb to this fixture surface.
     *
     * <p>Idempotent (the same set posted twice converges to the same rows) and validated <b>before</b>
     * anything is written: a self-edge or an edge that would close a cycle answers {@code 422
     * REPORTING_EDGE_INVALID} and leaves the relation untouched.
     */
    @PostMapping("/internal/bootstrap/reporting-edges")
    @Transactional
    public ResponseEntity<Map<String, Integer>> setReportingEdges(
            @RequestBody SetReportingEdges body) {
        int written = supervision.replaceReportsOf(body.managerId(), body.reportIds());
        return ResponseEntity.ok(Map.of("reportCount", written));
    }

    public record EnsureUser(String subject, String displayName) {
    }

    /** The declarative edge set for one manager; {@code reportIds} may be empty (removes them all). */
    public record SetReportingEdges(UUID managerId, List<UUID> reportIds) {
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
