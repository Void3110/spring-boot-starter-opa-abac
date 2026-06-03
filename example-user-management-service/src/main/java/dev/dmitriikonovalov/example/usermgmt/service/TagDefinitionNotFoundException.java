package dev.dmitriikonovalov.example.usermgmt.service;

import java.util.UUID;

/** Thrown when a team-scoped tag key is not found. → 404 Not Found. */
public class TagDefinitionNotFoundException extends RuntimeException {

    public TagDefinitionNotFoundException(UUID teamId, String key) {
        super("Tag key '" + key + "' not found on team " + teamId);
    }
}
