package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.security.LibraryErrorCode;
import dev.dmitriikonovalov.opaabac.security.ProblemDetail;
import dev.dmitriikonovalov.opaabac.security.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the two Spring-DAO conflict shapes — an optimistic-lock race
 * ({@link OptimisticLockingFailureException}) and a constraint violation
 * ({@link DataIntegrityViolationException}, e.g. deleting a row another row still references) — to
 * {@code 409} {@code STATE_CONFLICT} {@code application/problem+json}, the same body shape
 * {@code AbstractProblemAdvice} produces. Without this, both surface as {@code 500} from the default
 * error handling, hiding a client-resolvable conflict behind a server error.
 *
 * <p>It lives in the <em>starter</em>, not in {@code AbstractProblemAdvice}, because the shared base
 * must stay loadable for adopters without Spring-DAO on the classpath; here the auto-configuration
 * guards registration with {@code @ConditionalOnClass}. It is deliberately a standalone advice (not an
 * {@code AbstractProblemAdvice} subclass), so it never duplicates the base's handlers against the
 * application's own advice. The detail is static — no constraint names, SQL, or entity internals.
 */
@RestControllerAdvice
public class PersistenceConflictProblemAdvice {

    private final ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();

    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ProblemDetail> handlePersistenceConflict(
            RuntimeException ex, HttpServletRequest request) {
        String instance = request != null ? request.getRequestURI() : null;
        return problemDetailFactory.response(
                LibraryErrorCode.STATE_CONFLICT.status(),
                LibraryErrorCode.STATE_CONFLICT,
                "The change conflicts with the current state of the resource; re-read and retry",
                instance);
    }
}
