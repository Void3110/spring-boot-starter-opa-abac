package dev.dmitriikonovalov.example.usermgmt.service;

import java.util.UUID;

/** Thrown when a team-scoped custom role does not exist. → 404 Not Found. */
public class RoleNotFoundException extends RuntimeException {

    public RoleNotFoundException(UUID teamId, String code) {
        super("No custom role '" + code + "' on team " + teamId);
    }
}
