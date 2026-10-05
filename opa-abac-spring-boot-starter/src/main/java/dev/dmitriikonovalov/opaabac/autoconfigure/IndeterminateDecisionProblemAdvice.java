package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;
import dev.dmitriikonovalov.opaabac.security.AuthorizationIndeterminateException;
import dev.dmitriikonovalov.opaabac.security.LibraryErrorCode;
import dev.dmitriikonovalov.opaabac.security.ProblemDetail;
import dev.dmitriikonovalov.opaabac.security.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps "the authorization layer could not decide" (ADR 0037 §8) to {@code 503}
 * {@code DEPENDENCY_UNAVAILABLE} {@code application/problem+json} for an application that does <em>not</em>
 * extend {@code AbstractProblemAdvice} — which carries the same mapping itself. Without it, the method gate's
 * {@link AuthorizationIndeterminateException} would be rendered as a {@code 403} (it is an
 * {@code AccessDeniedException}) and a list query's raw {@link DecisionIndeterminateException} as a
 * {@code 500}.
 *
 * <h2>Why high precedence, and why only when the base advice is absent</h2>
 * Spring MVC asks advices <em>in order</em> and the first one with any matching handler wins — not the most
 * specific handler across advices. An application's own {@code AccessDeniedException} → {@code 403} handler,
 * asked first, would therefore claim the gate's subtype. Ordered ahead of it, this advice answers the two
 * family types first; registered only when no {@code AbstractProblemAdvice} bean exists, it never duplicates
 * the base's handlers (there, one advice holds both and the most specific wins). Within one advice Spring also
 * matches along the cause chain, so this advice declares exactly the two family types and nothing broader.
 *
 * <p>Deliberately a standalone advice (not an {@code AbstractProblemAdvice} subclass), like the starter's other
 * mappings. The request was refused — nothing ran; "not now" is the honest answer, and no {@code Retry-After}
 * is invented.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IndeterminateDecisionProblemAdvice {

    static final String DETAIL = "Authorization is temporarily unavailable";

    private final ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();

    @ExceptionHandler({AuthorizationIndeterminateException.class, DecisionIndeterminateException.class})
    public ResponseEntity<ProblemDetail> handleIndeterminate(RuntimeException ex, HttpServletRequest request) {
        String instance = request != null ? request.getRequestURI() : null;
        return problemDetailFactory.response(
                LibraryErrorCode.DEPENDENCY_UNAVAILABLE.status(),
                LibraryErrorCode.DEPENDENCY_UNAVAILABLE,
                DETAIL,
                instance);
    }
}
