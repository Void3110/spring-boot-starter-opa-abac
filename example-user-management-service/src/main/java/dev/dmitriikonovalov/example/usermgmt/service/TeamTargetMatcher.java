package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import java.util.UUID;

/**
 * Decides whether a team's team-target governs a given resource — the pluggable seam between a resource
 * and the team that owns it. The default {@link ExactTeamTargetMatcher} matches only the exact
 * {@code (type, id)}; a later, additive matcher can walk the catalog hierarchy (a team-target on a
 * {@code catalog} granting on its categories/products), which ties into Phase-5 hierarchical
 * authorization — hence the seam now, the hierarchy later.
 */
@FunctionalInterface
public interface TeamTargetMatcher {

    boolean matches(Team team, String resourceType, UUID resourceId);
}
