package dev.dmitriikonovalov.opaabac.security;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Builds RFC-7807 {@link ProblemDetail} bodies and {@code application/problem+json}
 * {@link ResponseEntity}s from an {@link ApiErrorCode}.
 *
 * <p>This is the single place that assembles the seven canonical members, so the per-service advices stay
 * thin (they only route an exception to a {@code (status, ApiErrorCode, detail, instance)} tuple). The
 * {@link ApiErrorCode} interface is the seam: a library code or an application's own code plugs in
 * unchanged.
 *
 * <p>The factory holds no decision logic — it never authorizes; it only shapes a body for a status that
 * has already been decided by the caller. Building a {@link ProblemDetail} for a {@code 403} does not turn
 * a deny into an allow; it renders the deny as a problem body.
 */
public final class ProblemDetailFactory {

    /**
     * Build a {@link ProblemDetail} body.
     *
     * @param status the HTTP status (its numeric value lands in {@code status})
     * @param code the error code (supplies {@code type}, {@code title}, {@code errorCode})
     * @param detail the human, instance-specific explanation (may be {@code null})
     * @param instance the request path (correlation; may be {@code null})
     */
    public ProblemDetail body(HttpStatus status, ApiErrorCode code, String detail, String instance) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        return new ProblemDetail(
                code.problemType(),
                code.title(),
                status.value(),
                detail,
                instance,
                code.code(),
                OffsetDateTime.now());
    }

    /**
     * Build a {@code ResponseEntity<ProblemDetail>} at {@code Content-Type: application/problem+json}
     * with the status carried by {@code code}'s mapping (the caller passes the resolved status).
     */
    public ResponseEntity<ProblemDetail> response(
            HttpStatus status, ApiErrorCode code, String detail, String instance) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body(status, code, detail, instance));
    }
}
