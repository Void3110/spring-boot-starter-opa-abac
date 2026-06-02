package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;

/**
 * A membership paired with its bound role's human-readable {@code roleCode} — what the API exposes
 * (the entity stores a {@code roleDefinitionId}). Returned by {@link MembershipService} so the web
 * layer never touches the role repository.
 */
public record MembershipView(TeamMembership membership, String roleCode) {
}
