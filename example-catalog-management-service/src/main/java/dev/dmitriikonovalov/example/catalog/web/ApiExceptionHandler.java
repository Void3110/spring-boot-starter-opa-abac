package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.IllegalTagAssignmentException;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionFetchException;
import dev.dmitriikonovalov.example.catalog.openapi.model.ApiError;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        var first = ex.getBindingResult().getFieldErrors().stream().findFirst();
        var message = first
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Validation failed");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /** An illegal assigned tag (unknown key / enum miss / cardinality / pattern). → 422; never stored. */
    @ExceptionHandler(IllegalTagAssignmentException.class)
    public ResponseEntity<ApiError> handleIllegalTag(IllegalTagAssignmentException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    /** The dictionary could not be fetched — fail-closed: reject the write rather than store untagged. */
    @ExceptionHandler(TagDefinitionFetchException.class)
    public ResponseEntity<ApiError> handleTagFetch(TagDefinitionFetchException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        var body = new ApiError()
                .status(status.value())
                .message(message)
                .timestamp(OffsetDateTime.now());
        return ResponseEntity.status(status).body(body);
    }
}
