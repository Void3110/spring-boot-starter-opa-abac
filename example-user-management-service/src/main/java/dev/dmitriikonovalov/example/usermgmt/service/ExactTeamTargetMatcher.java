package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The default {@link TeamTargetMatcher}: a team governs a resource only when its team-target is exactly
 * that resource ({@code targetType == resourceType && targetId == resourceId}). Hierarchy walking is a
 * later, additive matcher (Phase 5); the app overrides this bean to swap it in.
 */
@Component
public class ExactTeamTargetMatcher implements TeamTargetMatcher {

    @Override
    public boolean matches(Team team, String resourceType, UUID resourceId) {
        return team.getTargetType().equals(resourceType) && team.getTargetId().equals(resourceId);
    }
}
