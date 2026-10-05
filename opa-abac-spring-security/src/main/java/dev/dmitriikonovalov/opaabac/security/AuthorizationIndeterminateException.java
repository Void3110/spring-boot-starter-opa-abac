package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;
import java.util.Objects;
import org.springframework.security.access.AuthorizationServiceException;

/**
 * An authorization gate <strong>could not make its decision</strong> (ADR 0037 §5) — the policy engine
 * could not be asked or did not answer, or an input the decision needed (the caller's role) could not be
 * fetched. Thrown by {@link OpaPreAuthorizeAuthorizationManager} and {@link OpaAuthorizationManager} in
 * place of a denial, with the core {@link DecisionIndeterminateException} as its cause.
 *
 * <h2>Why this type</h2>
 * It is a Spring Security {@link AuthorizationServiceException} — Spring's own type for "an authorization
 * request could not be processed due to a system problem" — and therefore an
 * {@link org.springframework.security.access.AccessDeniedException}: <strong>every handler that catches
 * {@code AccessDeniedException} still denies</strong>, so nothing that was refused before is let through.
 * An application that wants to answer "not now" (a 503) instead of "no" (a 403) catches this subtype;
 * {@link AbstractProblemAdvice} does so out of the box.
 *
 * <p>It is deliberately <em>not</em> an {@code AuthorizationDeniedException}: the method-security
 * interceptor routes only that type to a {@code MethodAuthorizationDeniedHandler}, and a handler that
 * masks a denial (a {@code null} or redacted value with a 200) would silently mask an outage too. Cost,
 * accepted: Spring publishes no {@code AuthorizationDeniedEvent} for it — the operational log records the
 * failure where it is classified.
 */
public class AuthorizationIndeterminateException extends AuthorizationServiceException {

    /**
     * @param message a log-facing description — never rendered to a client
     * @param cause   why no decision was possible; never {@code null}
     */
    public AuthorizationIndeterminateException(String message, DecisionIndeterminateException cause) {
        super(message, Objects.requireNonNull(cause, "cause"));
    }
}
