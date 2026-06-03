package dev.dmitriikonovalov.example.usermgmt.service;

/** Thrown when a custom role code clashes with a system role or an existing team role. → 409 Conflict. */
public class RoleConflictException extends RuntimeException {

    public RoleConflictException(String message) {
        super(message);
    }
}
