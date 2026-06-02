package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.openapi.model.ApiError;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipConflictException;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipNotFoundException;
import dev.dmitriikonovalov.example.usermgmt.service.RoleConflictException;
import dev.dmitriikonovalov.example.usermgmt.service.RoleNotFoundException;
import dev.dmitriikonovalov.example.usermgmt.service.SubsetRuleViolationException;
import dev.dmitriikonovalov.example.usermgmt.service.SystemRoleImmutableException;
import dev.dmitriikonovalov.example.usermgmt.service.TeamTargetExistsException;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({
        NotFoundException.class,
        MembershipNotFoundException.class,
        RoleNotFoundException.class
    })
    public ResponseEntity<ApiError> handleNotFound(RuntimeException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({
        TeamTargetExistsException.class,
        MembershipConflictException.class,
        RoleConflictException.class,
        SystemRoleImmutableException.class
    })
    public ResponseEntity<ApiError> handleConflict(RuntimeException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(SubsetRuleViolationException.class)
    public ResponseEntity<ApiError> handleSubsetRule(SubsetRuleViolationException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
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
