package dev.dmitriikonovalov.example.usermgmt.service;

/**
 * Thrown when a submitted tag definition is malformed — e.g. an ENUM with no allowed values, a bad key
 * format, an allowed-values set over the cap, or a contradictory shape. → 422 Unprocessable Entity.
 */
public class InvalidTagDefinitionException extends RuntimeException {

    public InvalidTagDefinitionException(String message) {
        super(message);
    }
}
