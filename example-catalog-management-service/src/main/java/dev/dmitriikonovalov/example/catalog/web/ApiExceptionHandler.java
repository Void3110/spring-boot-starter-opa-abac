package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.IllegalTagAssignmentException;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionFetchException;
import dev.dmitriikonovalov.example.catalog.config.TagOperatorManagedException;
import dev.dmitriikonovalov.opaabac.security.AbstractProblemAdvice;
import dev.dmitriikonovalov.opaabac.security.LibraryErrorCode;
import dev.dmitriikonovalov.opaabac.security.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps catalog exceptions to RFC-7807 {@code application/problem+json} bodies with a typed
 * {@code errorCode}.
 *
 * <p>Extends {@link AbstractProblemAdvice}, which supplies the body builder and the inherited
 * {@code AccessDeniedException} → {@code 403 ACCESS_DENIED} mapping (so a denied {@code @OpaPreAuthorize}
 * call also lands as {@code problem+json}). The status for each exception is <strong>unchanged</strong>
 * from before — only the body shape and the typed code are new. Every catalog failure maps cleanly to a
 * {@link LibraryErrorCode}; {@link CatalogErrorCode} carries the app-specific ones (today:
 * {@code TAG_OPERATOR_MANAGED}).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends AbstractProblemAdvice {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.RESOURCE_NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        var first = ex.getBindingResult().getFieldErrors().stream().findFirst();
        var detail = first
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Validation failed");
        return problem(LibraryErrorCode.VALIDATION_FAILED, detail, request);
    }

    /**
     * A constraint violation on a request <em>parameter</em> (e.g. the paged lists' {@code page >= 0} /
     * {@code 1 <= perPage <= 100} bounds, generated from the spec into the {@code @Validated} API
     * interface — violations surface as a {@link ConstraintViolationException} from method validation).
     * Same typed code as a body violation: {@code 400 VALIDATION_FAILED}, no clamping.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleParameterValidation(
            ConstraintViolationException ex, HttpServletRequest request) {
        var detail = ex.getConstraintViolations().stream()
                .findFirst()
                .map(ApiExceptionHandler::violationDetail)
                .orElse("Validation failed");
        return problem(LibraryErrorCode.VALIDATION_FAILED, detail, request);
    }

    /** {@code listCategories.perPage: must be …} → {@code perPage: must be …} (the param, not the method). */
    private static String violationDetail(ConstraintViolation<?> violation) {
        String path = String.valueOf(violation.getPropertyPath());
        String param = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return param + ": " + violation.getMessage();
    }

    /** An illegal assigned tag (unknown key / enum miss / cardinality / pattern). → 422; never stored. */
    @ExceptionHandler(IllegalTagAssignmentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalTag(
            IllegalTagAssignmentException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.TAG_VALUE_ILLEGAL, ex.getMessage(), request);
    }

    /**
     * A public write that would assign, re-value or strip an <b>operator-managed</b> key (ADR 0030 §3).
     * → 409 with the catalog's own {@code TAG_OPERATOR_MANAGED}: a conflict, not a validation failure —
     * the submitted map is well-formed and the key legal; it is the resource's current state the caller
     * may not move.
     */
    @ExceptionHandler(TagOperatorManagedException.class)
    public ResponseEntity<ProblemDetail> handleOperatorManagedTag(
            TagOperatorManagedException ex, HttpServletRequest request) {
        return problem(CatalogErrorCode.TAG_OPERATOR_MANAGED, ex.getMessage(), request);
    }

    /** The dictionary could not be fetched — fail-closed: reject the write rather than store untagged. */
    @ExceptionHandler(TagDefinitionFetchException.class)
    public ResponseEntity<ProblemDetail> handleTagFetch(
            TagDefinitionFetchException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.DEPENDENCY_UNAVAILABLE, ex.getMessage(), request);
    }
}
