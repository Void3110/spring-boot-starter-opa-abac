package dev.dmitriikonovalov.example.catalog.config;

/**
 * Thrown when the catalog cannot fetch the applicable tag definitions from the user-service (a non-200,
 * timeout, connection refused, or malformed body). This is the <b>fail-closed</b> signal for tag
 * assignment: rather than persist an unvalidated tag, the catalog rejects the write (→ 503). It is
 * deliberately <em>not</em> an empty "all-allowed" set — a definitions outage must never widen what is
 * legal to assign.
 */
public class TagDefinitionFetchException extends RuntimeException {

    public TagDefinitionFetchException(String message) {
        super(message);
    }
}
