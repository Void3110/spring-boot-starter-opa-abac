package dev.dmitriikonovalov.example.usermgmt.config;

import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@code team} to its {@link AbacResource} (a {@link Team}, which extends the secured base
 * and carries its attributes), so the request-scoped {@code AbacResourceCache} can hold the resolved
 * snapshot for the action-enrichment advice to read (Phase 6).
 *
 * <p>Registering this resolver is what <em>activates</em> enrichment for the user-management service (the
 * starter's advice auto-config is gated on an {@code AbacResourceResolver} bean + the cache it produces).
 *
 * <p><strong>The ungated-read degrade (a documented, correct outcome):</strong> {@code getTeam} /
 * {@code listTeams} carry no {@code @OpaPreAuthorize} (the owner-on-create bootstrap), so no gate ever
 * write-throughs a {@code Team} into the cache. The advice resolves a team's attributes <em>via the cache
 * only</em> — it never re-resolves in the read path (ADR 0016 rejects that) — so a {@code getTeam}
 * response <strong>cache-misses and omits {@code _actions}</strong>: the visible degrade (omit, never
 * fabricate). If a later phase gates {@code getTeam}, enrichment lights up automatically with no change
 * here.
 */
@Component
public class TeamResourceResolver implements AbacResourceResolver {

    private final TeamRepository teams;

    public TeamResourceResolver(TeamRepository teams) {
        this.teams = teams;
    }

    @Override
    public Optional<AbacResource> resolve(String resourceType, String resourceId) {
        if (!"team".equals(resourceType)) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(resourceId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return teams.findById(id).map(team -> (AbacResource) team);
    }
}
