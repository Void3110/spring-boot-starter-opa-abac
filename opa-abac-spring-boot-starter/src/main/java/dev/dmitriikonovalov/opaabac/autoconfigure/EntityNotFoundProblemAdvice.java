package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.data.service.EntityNotFoundException;
import dev.dmitriikonovalov.opaabac.security.LibraryErrorCode;
import dev.dmitriikonovalov.opaabac.security.ProblemDetail;
import dev.dmitriikonovalov.opaabac.security.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the library's {@link EntityNotFoundException} (thrown by {@code AbstractCrudService} when a
 * required id does not exist — e.g. the row was deleted between an update's read and write) to
 * {@code 404} {@code RESOURCE_NOT_FOUND} {@code application/problem+json}. Without this, an
 * update-vs-delete race surfaces as {@code 500} from the default error handling.
 *
 * <p>This does not weaken the gate's anti-enumeration posture: a missing id behind an annotated
 * {@code resourceId} is denied {@code 403} <em>before</em> the handler runs; this mapping only fires
 * for handler-level lookups that were already allowed.
 *
 * <p>It lives in the <em>starter</em> because {@code AbstractProblemAdvice} (spring-security module)
 * must stay loadable without the spring-data module on the classpath; the auto-configuration guards
 * registration with {@code @ConditionalOnClass}. Deliberately a standalone advice (not an
 * {@code AbstractProblemAdvice} subclass), so it never duplicates the base's handlers against the
 * application's own advice.
 */
@RestControllerAdvice
public class EntityNotFoundProblemAdvice {

    private final ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEntityNotFound(
            EntityNotFoundException ex, HttpServletRequest request) {
        String instance = request != null ? request.getRequestURI() : null;
        return problemDetailFactory.response(
                LibraryErrorCode.RESOURCE_NOT_FOUND.status(),
                LibraryErrorCode.RESOURCE_NOT_FOUND,
                "The requested resource does not exist",
                instance);
    }
}
