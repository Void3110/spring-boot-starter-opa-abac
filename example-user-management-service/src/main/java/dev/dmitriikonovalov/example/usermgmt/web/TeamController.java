package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.api.TeamApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.CreateTeamRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Team;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TeamPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TransferOwnershipRequest;
import dev.dmitriikonovalov.example.usermgmt.service.CallerIdentity;
import dev.dmitriikonovalov.example.usermgmt.service.TeamService;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.UUID;
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

    public TeamController(
            TeamRepository teams, TeamService teamService, CallerIdentity callerIdentity) {
        this.teams = teams;
        this.teamService = teamService;
        this.callerIdentity = callerIdentity;
    }

    @Override
    public ResponseEntity<Team> createTeam(CreateTeamRequest request) {
        // bootstrap: pre-membership, authenticated-only by design — creating your first team precedes any
        // membership to authorize against (owner-on-create), so this endpoint is deliberately ungated
        // (no @OpaPreAuthorize). The creator becomes the owner atomically inside TeamService.
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
