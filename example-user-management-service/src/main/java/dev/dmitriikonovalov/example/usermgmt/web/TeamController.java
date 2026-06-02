package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.api.TeamApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Team;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Team read surface (ticket 2). Team <em>creation</em> is owner-on-create (ticket 3) and lives in
 * {@code TeamService}; membership / role-def management and transfer are later tickets. Read-only
 * here, so it talks to the repository directly, catalog-style.
 */
@RestController
public class TeamController implements TeamApi {

    private final TeamRepository teams;

    public TeamController(TeamRepository teams) {
        this.teams = teams;
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
}
