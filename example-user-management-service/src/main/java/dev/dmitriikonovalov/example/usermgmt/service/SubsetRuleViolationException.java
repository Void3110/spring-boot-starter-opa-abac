package dev.dmitriikonovalov.example.usermgmt.service;

/**
 * Thrown when an assign/define would grant more than the actor holds (the no-self-escalation subset
 * rule). Mapped to 422 Unprocessable Entity by the API exception handler.
 */
public class SubsetRuleViolationException extends RuntimeException {

    public SubsetRuleViolationException(String message) {
        super(message);
    }
}
