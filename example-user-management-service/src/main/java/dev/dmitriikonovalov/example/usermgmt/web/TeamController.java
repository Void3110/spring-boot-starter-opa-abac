package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.api.TeamApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.CreateTeamRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Team;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TransferOwnershipRequest;
import dev.dmitriikonovalov.example.usermgmt.service.CallerIdentity;
import dev.dmitriikonovalov.example.usermgmt.service.TeamService;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

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
        UUID creator = callerIdentity.requireActingUserId(request.getCreatorUserId());
        var team = teamService.createWithOwner(
                creator, request.getName(), request.getTargetType(), request.getTargetId());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserMgmtMapper.toDto(team));
    }

    @Override
    public ResponseEntity<List<Team>> listTeams() {
        var result = teams.findAll().stream().map(UserMgmtMapper::toDto).toList();
        return ResponseEntity.ok(result);
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
