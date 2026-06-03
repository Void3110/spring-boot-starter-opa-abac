package dev.dmitriikonovalov.example.usermgmt.service;

/** Thrown when an update/delete targets an immutable system role. → 409 Conflict. */
public class SystemRoleImmutableException extends RuntimeException {

    public SystemRoleImmutableException(String code) {
        super("System role '" + code + "' is immutable");
    }
}
