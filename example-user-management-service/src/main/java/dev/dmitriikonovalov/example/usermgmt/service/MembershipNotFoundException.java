package dev.dmitriikonovalov.example.usermgmt.service;

import java.util.UUID;

/** Thrown when a membership for {@code (team, user)} does not exist. Mapped to 404 Not Found. */
public class MembershipNotFoundException extends RuntimeException {

    public MembershipNotFoundException(UUID teamId, UUID userId) {
        super("No membership for user " + userId + " on team " + teamId);
    }
}
