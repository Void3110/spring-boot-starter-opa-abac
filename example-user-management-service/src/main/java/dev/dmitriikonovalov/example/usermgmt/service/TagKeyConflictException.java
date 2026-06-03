package dev.dmitriikonovalov.example.usermgmt.service;

/** Thrown when defining a key that already exists in the same scope (global or this team). → 409 Conflict. */
public class TagKeyConflictException extends RuntimeException {

    public TagKeyConflictException(String message) {
        super(message);
    }
}
