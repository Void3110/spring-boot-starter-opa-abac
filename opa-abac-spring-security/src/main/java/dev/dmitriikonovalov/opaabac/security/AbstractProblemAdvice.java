package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.VersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * A reusable {@code @RestControllerAdvice} base each service's exception handler extends.
 *
 * <p>It owns two things every service shares:
 * <ul>
 *   <li>a {@link ProblemDetailFactory} and convenience {@link #problem} builders, so a subclass only maps
 *       its exceptions to {@code (status, ApiErrorCode, detail)} tuples; and</li>
 *   <li>the {@link AccessDeniedException} / {@link AuthorizationDeniedException} → {@code 403}
 *       {@link LibraryErrorCode#ACCESS_DENIED} mapping. Without this, a {@code 403} raised by
 *       {@code @OpaPreAuthorize} / {@code OpaAuthorizationManager} would be rendered by Spring Security's
 *       default error handling, <em>not</em> as {@code application/problem+json} — so denied calls would
 *       miss the contract. Putting it in the shared base makes the deny a first-class problem body that
 *       both services inherit.</li>
 * </ul>
 *
 * <p><strong>This renders a deny; it does not authorize.</strong> Reaching this handler means the
 * decision already denied — it shapes the {@code 403} body and never turns a deny into an allow.
 */
public abstract class AbstractProblemAdvice {

    private final ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();

    /**
     * Render a {@code 403 application/problem+json} for an access denial raised by the authorization
     * layer. The denial has already happened; this only shapes the body.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            RuntimeException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.ACCESS_DENIED, "Access denied", request);
    }

    /**
     * Render a {@code 409 application/problem+json} for a detected version conflict: the resource
     * changed between the authorization decision and the action ({@code VersionGuard}). The client
     * re-reads and retries; the retry's gate decides on the new state. The detail is deliberately
     * static — the body carries no versions or internals.
     */
    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ProblemDetail> handleVersionConflict(
            VersionConflictException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.STATE_CONFLICT,
                "The resource changed after it was authorized; re-read and retry", request);
    }

    /**
     * Build a {@code problem+json} response, taking the status from the code itself. Works for any
     * {@link ApiErrorCode} — a {@link LibraryErrorCode} or an application's own enum — so a subclass never
     * re-invents the status at the call site.
     */
    protected ResponseEntity<ProblemDetail> problem(
            ApiErrorCode code, String detail, HttpServletRequest request) {
        return problem(code.status(), code, detail, request);
    }

    /** Build a {@code problem+json} response with an explicit status (rarely needed). */
    protected ResponseEntity<ProblemDetail> problem(
            HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request) {
        String instance = request != null ? request.getRequestURI() : null;
        return problemDetailFactory.response(status, code, detail, instance);
    }

    /** The shared factory, for subclasses that need to build a body directly. */
    protected ProblemDetailFactory problemDetailFactory() {
        return problemDetailFactory;
    }
}
