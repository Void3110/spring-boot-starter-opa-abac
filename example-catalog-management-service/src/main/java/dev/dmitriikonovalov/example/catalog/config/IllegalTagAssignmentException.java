package dev.dmitriikonovalov.example.catalog.config;

/**
 * Thrown when an assigned tag is not legal against the dictionary — an unknown key, an enum miss, a
 * cardinality mismatch, a pattern miss. → 422 Unprocessable Entity. The offending key is named; the tag
 * is never silently dropped or stored.
 */
public class IllegalTagAssignmentException extends RuntimeException {

    public IllegalTagAssignmentException(String message) {
        super(message);
    }
}
