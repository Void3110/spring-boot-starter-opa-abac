package dev.dmitriikonovalov.opaabac.security;

import org.springframework.http.HttpStatus;

/**
 * The error codes the library itself raises or that are generic across services — the
 * authorization-shaped failures and the common HTTP outcomes.
 *
 * <p>Each constant carries the {@link HttpStatus} it maps to, so an advice resolves
 * {@code (status, errorCode)} from a single source. Application-specific failures that a client would
 * branch on <em>within</em> a status (e.g. distinguishing two different {@code 409} conflicts) belong in
 * an application's own {@link ApiErrorCode} enum, not here — the granularity is semantic, one code per
 * distinct, client-actionable failure (not one per status).
 */
public enum LibraryErrorCode implements ApiErrorCode {

    /** OPA-deny / unauthenticated / unresolved subject — the access decision said no. */
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),

    /** A required dependency (e.g. the tag dictionary) was unavailable; the request was rejected, not served degraded. */
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dependency unavailable"),

    /** The request body or parameters failed validation. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),

    /** The addressed resource does not exist (or is not visible by the requested path). */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    /** The request conflicts with the current state of the resource. */
    STATE_CONFLICT(HttpStatus.CONFLICT, "State conflict"),

    /** A tag value is not permitted by the dictionary. */
    TAG_VALUE_ILLEGAL(HttpStatus.UNPROCESSABLE_ENTITY, "Tag value not permitted by the dictionary"),

    /** A role assignment would exceed the granter's own permissions (the subset rule). */
    ROLE_SUBSET_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Role assignment violates the subset rule");

    private final HttpStatus status;
    private final String title;

    LibraryErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    /** The HTTP status this failure maps to. */
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String title() {
        return title;
    }
}
