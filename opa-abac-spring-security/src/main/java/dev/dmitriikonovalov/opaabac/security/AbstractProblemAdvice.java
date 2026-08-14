package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.DenyReason;
import dev.dmitriikonovalov.opaabac.core.VersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private static final Logger log = LoggerFactory.getLogger(AbstractProblemAdvice.class);

    /**
     * The DOMAIN-NEUTRAL default sentence — see {@link #stepUpChallengeDescription()}.
     *
     * <p>It deliberately says nothing about <em>why</em> the resource is sensitive. This class is
     * published: an adopter whose {@code insufficient_user_authentication} reason guards a payment
     * confirmation or an admin action would otherwise have this library assert a false, domain-
     * inappropriate fact about their resource to the caller (ADR 0030 §8 Amendment 7's rule, applied
     * to the wire-visible half — the audit event's vocabulary was fixed first, this is its twin).
     */
    private static final String DEFAULT_STEP_UP_DETAIL =
            "Re-authentication with a stronger, fresher factor is required";

    private final ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();

    /**
     * The one human-readable sentence the challenge and its problem body share, so the
     * {@code error_description} a client reads on the header and the {@code detail} it reads in the body
     * can never drift apart.
     *
     * <p><strong>Override to speak your own domain.</strong> The default is deliberately generic; a
     * service that knows what its step-up actually protects should say so — this repo's example
     * catalog service overrides it with its production-tier wording. The value is emitted inside a
     * quoted HTTP header parameter, so an override containing a quote or CR/LF suppresses the
     * challenge (the same allowlist that guards the policy-supplied parameters).
     *
     * @return the sentence to put in {@code error_description} and the problem {@code detail}
     */
    protected String stepUpChallengeDescription() {
        return DEFAULT_STEP_UP_DETAIL;
    }

    /**
     * Render a {@code 403 application/problem+json} for an access denial raised by the authorization
     * layer — or, when the denial was <em>only</em> for want of a fresh second factor, a {@code 401}
     * with an RFC 9470 {@code WWW-Authenticate} challenge (ADR 0030 §7). The denial has already
     * happened; this only shapes the response.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            RuntimeException ex, HttpServletRequest request) {
        StepUpRequiredDecision stepUp = stepUpDecision(ex);
        if (stepUp != null) {
            String challenge = challengeFor(stepUp.reason());
            if (challenge != null) {
                AbacAuditLogger.stepUpChallenged(currentSubjectId(), stepUp);
                ResponseEntity<ProblemDetail> base =
                        problem(LibraryErrorCode.STEP_UP_REQUIRED, stepUpChallengeDescription(), request);
                return ResponseEntity.status(base.getStatusCode())
                        .headers(base.getHeaders())
                        .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
                        .body(base.getBody());
            }
        }
        return problem(LibraryErrorCode.ACCESS_DENIED, "Access denied", request);
    }

    /**
     * The {@link StepUpRequiredDecision} behind this exception, or {@code null} for every other denial.
     *
     * <p>Spring Security's {@code AuthorizationManager.verify} throws
     * {@code new AuthorizationDeniedException("Access Denied", result)} carrying the manager's own result
     * object, so the subclass arrives here intact (verified against spring-security-core, not assumed).
     */
    private static StepUpRequiredDecision stepUpDecision(RuntimeException ex) {
        if (ex instanceof AuthorizationDeniedException denied
                && denied.getAuthorizationResult() instanceof StepUpRequiredDecision stepUp) {
            return stepUp;
        }
        return null;
    }

    /**
     * The RFC 9470 challenge for a reason, or {@code null} when one must not be emitted.
     *
     * <p>Four rejections, all of which fall back to the ordinary 403:
     * <ul>
     *   <li><strong>An unrecognized reason type.</strong> The one type this library knows how to answer
     *       is {@link DenyReason#INSUFFICIENT_USER_AUTHENTICATION} — a fresh second factor. Any other
     *       well-formed type (a policy typo, a future reason class, a tampered data document) would mint
     *       a 401 whose {@code error} code no RFC 9470 client can act on: an unanswerable challenge is
     *       ADR 0030 §7's infinite loop wearing a different hat. An unknown reason is a plain deny.</li>
     *   <li><strong>An incomplete reason.</strong> A challenge without {@code max_age} lets the client
     *       re-authenticate against a still-valid session, receive the same stale {@code auth_time}, and
     *       be challenged again — ADR 0030 §7's infinite loop. A half-formed challenge is worse than
     *       none.</li>
     *   <li><strong>A non-positive window.</strong> {@code max_age <= 0} is well-typed but
     *       unsatisfiable — no re-authentication is ever "within zero seconds" — so the challenge would
     *       be the same §7 loop by arithmetic rather than by omission. The example policy guards its own
     *       data, but this library is published for adopters who write their own, so the emitter refuses
     *       on its own terms rather than trusting the policy to have checked.</li>
     *   <li><strong>A value that cannot be safely quoted.</strong> The parameters originate in policy
     *       data, which is trusted but not this class's to trust <em>blindly</em>: a value carrying a
     *       quote or a CR/LF would break out of the quoted-string and, in the worst case, out of the
     *       header. The allowlist keeps a compromised or fat-fingered data document from turning a deny
     *       into a header-injection primitive.</li>
     * </ul>
     */
    private String challengeFor(DenyReason reason) {
        String description = stepUpChallengeDescription();
        if (!DenyReason.INSUFFICIENT_USER_AUTHENTICATION.equals(reason.type())) {
            log.warn("step-up challenge suppressed: unrecognized deny_reason type (denying 403)");
            return null;
        }
        if (!reason.isComplete()) {
            log.warn("step-up challenge suppressed: incomplete deny_reason (denying 403)");
            return null;
        }
        // A NON-POSITIVE window is well-typed but unsatisfiable — no re-authentication can ever be
        // "within 0 seconds" — so advertising it is the §7 loop with extra steps. The example policy
        // guards its own data, but this library is published for adopters who write their own: the
        // emitter must refuse the unanswerable challenge on its own terms, not trust the policy to.
        if (reason.maxAge() <= 0) {
            log.warn("step-up challenge suppressed: non-positive max_age {} (denying 403)",
                    reason.maxAge());
            return null;
        }
        if (!isSafeParameter(reason.type()) || !isSafeParameter(reason.requiredAcr())
                || !isSafeParameter(description)) {
            log.warn("step-up challenge suppressed: a parameter cannot be safely quoted (denying 403)");
            return null;
        }
        return "Bearer error=\"" + reason.type() + "\""
                + ", error_description=\"" + description + "\""
                + ", acr_values=\"" + reason.requiredAcr() + "\""
                + ", max_age=\"" + reason.maxAge() + "\"";
    }

    /**
     * Safe inside a quoted header parameter — no quote, no backslash, no CR/LF, no control characters.
     *
     * <p>Space and comma are admitted because one of the guarded values is PROSE (the challenge
     * description) and an ordinary English sentence contains both; they are unremarkable inside a
     * quoted-string. Everything that could break out of that string — or out of the header — is not:
     * quote, backslash, CR/LF, control characters. (Measured the hard way: the neutral default
     * sentence itself was suppressed by an allowlist that forbade the comma, which is precisely the
     * silent 403-downgrade an adopter writing a normal sentence would have hit.)
     */
    private static boolean isSafeParameter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == ':' || c == '/' || c == ' '
                    || c == ',';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** The challenged subject, or {@code null} — log-only, and never an input to anything. */
    private static String currentSubjectId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject().id();
        }
        return null;
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
