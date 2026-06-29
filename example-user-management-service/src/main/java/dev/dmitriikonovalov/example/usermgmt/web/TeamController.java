package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.api.TeamApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.CreateTeamRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Team;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TeamPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TransferOwnershipRequest;
import dev.dmitriikonovalov.example.usermgmt.service.CallerIdentity;
import dev.dmitriikonovalov.example.usermgmt.service.NotResourceOwnerException;
import dev.dmitriikonovalov.example.usermgmt.service.TeamService;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import dev.dmitriikonovalov.opaabac.security.ownership.ResourceOwnershipResolver;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Team read + create surface. Reads talk to the repository directly (catalog-style); <em>creation</em>
 * is owner-on-create and delegates to {@link TeamService} — the transactional bootstrap (Team + owner
 * membership in one tx) lives in the {@code service/} layer, never in the controller. Membership /
 * role-def management and transfer are later tickets.
 */
@RestController
public class TeamController implements TeamApi {

    private final TeamRepository teams;
    private final TeamService teamService;
    private final CallerIdentity callerIdentity;
    /** Present only when ownership verification is wired (abac.ownership.enabled); absent → fail-closed. */
    private final ObjectProvider<ResourceOwnershipResolver> ownershipResolver;

    public TeamController(
            TeamRepository teams,
            TeamService teamService,
            CallerIdentity callerIdentity,
            ObjectProvider<ResourceOwnershipResolver> ownershipResolver) {
        this.teams = teams;
        this.teamService = teamService;
        this.callerIdentity = callerIdentity;
        this.ownershipResolver = ownershipResolver;
    }

    @Override
    public ResponseEntity<Team> createTeam(CreateTeamRequest request) {
        // Pre-membership, authenticated-only by design — creating your first team precedes any membership
        // to authorize against (owner-on-create), so this endpoint carries no @OpaPreAuthorize. The creator
        // becomes the owner atomically inside TeamService.
        //
        // Slice B4 (ADR 0019) — the target-squatting guard. Before binding the target, verify the caller
        // OWNS it (created it) via the cross-service ResourceOwnershipResolver. A non-owner — or an
        // unverifiable check (resolver absent / owning service down / unknown type) — fails closed to 403.
        // This is the PUBLIC path; the /internal/bootstrap/teams seed path is a SEPARATE controller
        // (InternalBootstrapController) that never reaches here, so it bypasses by construction (trusted
        // in-network admin seam, permitAll, never gateway-exposed).
        requireOwnership(request.getTargetType(), request.getTargetId());

        UUID creator = callerIdentity.requireActingUserId(request.getCreatorUserId());
        var team = teamService.createWithOwner(
                creator, request.getName(), request.getTargetType(), request.getTargetId());
        var dto = UserMgmtMapper.toDto(team);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(dto.getId())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    /**
     * Fail-closed ownership gate for the public team-create: the caller must own {@code (targetType,
     * targetId)}. Throws {@link NotResourceOwnerException} (→ 403) when not the owner, when the resolver is
     * absent (ownership verification not wired), or when the caller has no resolvable subject — never a
     * default-allow. The resolver itself returns {@code false} (never throws) on every breach (unknown type
     * / owning service down / 404), so all of those collapse to this 403.
     */
    private void requireOwnership(String targetType, UUID targetId) {
        ResourceOwnershipResolver resolver = ownershipResolver.getIfAvailable();
        String subject = callerIdentity.currentSubject().orElse(null);
        if (resolver == null || subject == null || !resolver.isOwner(subject, targetType, targetId)) {
            throw new NotResourceOwnerException(targetType, targetId);
        }
    }

    @Override
    public ResponseEntity<TeamPage> listTeams(Integer page, Integer perPage) {
        var result = teams.findAll(PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(UserMgmtMapper.toTeamPage(result));
    }

    @Override
    public ResponseEntity<Team> getTeam(UUID teamId) {
        var entity = teams.findById(teamId)
                .orElseThrow(() -> new NotFoundException("Team not found: " + teamId));
        return ResponseEntity.ok(UserMgmtMapper.toDto(entity));
    }

    @Override
    @OpaPreAuthorize(
            action = "team:transfer-ownership", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<Void> transferOwnership(UUID teamId, TransferOwnershipRequest request) {
        teamService.transferOwnership(teamId, request.getNewOwnerUserId());
        return ResponseEntity.noContent().build();
    }
}
