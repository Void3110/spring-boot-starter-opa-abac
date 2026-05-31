package dev.dmitriikonovalov.example.catalog.web;

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

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        var body = new ApiError()
                .status(status.value())
                .message(message)
                .timestamp(OffsetDateTime.now());
        return ResponseEntity.status(status).body(body);
    }
}
