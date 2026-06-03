package dev.dmitriikonovalov.example.usermgmt.service;

import java.util.UUID;

/**
 * Thrown when a team already governs a given team-target — one team per {@code (targetType,
 * targetId)}. Mapped to 409 Conflict by the API exception handler.
 */
public class TeamTargetExistsException extends RuntimeException {

    public TeamTargetExistsException(String targetType, UUID targetId) {
        super("A team already governs " + targetType + " " + targetId);
    }
}
