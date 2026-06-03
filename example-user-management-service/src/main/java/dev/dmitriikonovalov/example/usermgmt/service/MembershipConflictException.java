package dev.dmitriikonovalov.example.usermgmt.service;

/** Thrown when adding a member who already belongs to the team. Mapped to 409 Conflict. */
public class MembershipConflictException extends RuntimeException {

    public MembershipConflictException(String message) {
        super(message);
    }
}
