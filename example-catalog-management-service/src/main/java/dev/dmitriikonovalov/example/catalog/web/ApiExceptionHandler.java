package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.IllegalTagAssignmentException;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionFetchException;
import dev.dmitriikonovalov.opaabac.security.AbstractProblemAdvice;
import dev.dmitriikonovalov.opaabac.security.LibraryErrorCode;
import dev.dmitriikonovalov.opaabac.security.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
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
 * {@link LibraryErrorCode}; see {@link CatalogErrorCode} for the (currently empty) app-specific extension
 * point.
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

    /** An illegal assigned tag (unknown key / enum miss / cardinality / pattern). → 422; never stored. */
    @ExceptionHandler(IllegalTagAssignmentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalTag(
            IllegalTagAssignmentException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.TAG_VALUE_ILLEGAL, ex.getMessage(), request);
    }

    /** The dictionary could not be fetched — fail-closed: reject the write rather than store untagged. */
    @ExceptionHandler(TagDefinitionFetchException.class)
    public ResponseEntity<ProblemDetail> handleTagFetch(
            TagDefinitionFetchException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.DEPENDENCY_UNAVAILABLE, ex.getMessage(), request);
    }
}
