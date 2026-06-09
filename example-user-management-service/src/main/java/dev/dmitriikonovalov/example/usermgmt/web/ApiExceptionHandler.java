package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.service.InvalidTagDefinitionException;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipConflictException;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipNotFoundException;
import dev.dmitriikonovalov.example.usermgmt.service.RoleConflictException;
import dev.dmitriikonovalov.example.usermgmt.service.RoleNotFoundException;
import dev.dmitriikonovalov.example.usermgmt.service.SubsetRuleViolationException;
import dev.dmitriikonovalov.example.usermgmt.service.SystemRoleImmutableException;
import dev.dmitriikonovalov.example.usermgmt.service.TagDefinitionImmutableException;
import dev.dmitriikonovalov.example.usermgmt.service.TagDefinitionNotFoundException;
import dev.dmitriikonovalov.example.usermgmt.service.TagKeyConflictException;
import dev.dmitriikonovalov.example.usermgmt.service.TeamTargetExistsException;
import dev.dmitriikonovalov.opaabac.security.AbstractProblemAdvice;
import dev.dmitriikonovalov.opaabac.security.LibraryErrorCode;
import dev.dmitriikonovalov.opaabac.security.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps user-service exceptions to RFC-7807 {@code application/problem+json} bodies with a typed
 * {@code errorCode}.
 *
 * <p>Extends {@link AbstractProblemAdvice} (the shared body builder + the inherited
 * {@code AccessDeniedException} → {@code 403 ACCESS_DENIED} mapping). The status for each exception is
 * <strong>unchanged</strong> from before. Per ADR 0011 §4 (semantic granularity) the {@code 409} conflict
 * group is split into distinct {@link UserMgmtErrorCode}s a client can branch on; generic failures
 * (not-found, validation) reuse {@link LibraryErrorCode}; the subset rule uses the library's
 * {@code ROLE_SUBSET_VIOLATION}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends AbstractProblemAdvice {

    // --- 404 not-found group → library RESOURCE_NOT_FOUND ---------------------

    @ExceptionHandler({
        NotFoundException.class,
        MembershipNotFoundException.class,
        RoleNotFoundException.class,
        TagDefinitionNotFoundException.class
    })
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.RESOURCE_NOT_FOUND, ex.getMessage(), request);
    }

    // --- 409 conflict group → distinct UserMgmtErrorCodes (semantic granularity) ---

    @ExceptionHandler(TeamTargetExistsException.class)
    public ResponseEntity<ProblemDetail> handleTeamTargetExists(
            TeamTargetExistsException ex, HttpServletRequest request) {
        return problem(UserMgmtErrorCode.TEAM_TARGET_EXISTS, ex.getMessage(), request);
    }

    @ExceptionHandler(MembershipConflictException.class)
    public ResponseEntity<ProblemDetail> handleMembershipConflict(
            MembershipConflictException ex, HttpServletRequest request) {
        return problem(UserMgmtErrorCode.MEMBERSHIP_CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(RoleConflictException.class)
    public ResponseEntity<ProblemDetail> handleRoleConflict(
            RoleConflictException ex, HttpServletRequest request) {
        return problem(UserMgmtErrorCode.ROLE_CODE_CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(SystemRoleImmutableException.class)
    public ResponseEntity<ProblemDetail> handleRoleImmutable(
            SystemRoleImmutableException ex, HttpServletRequest request) {
        return problem(UserMgmtErrorCode.ROLE_IMMUTABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(TagKeyConflictException.class)
    public ResponseEntity<ProblemDetail> handleTagKeyConflict(
            TagKeyConflictException ex, HttpServletRequest request) {
        return problem(UserMgmtErrorCode.TAG_KEY_CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(TagDefinitionImmutableException.class)
    public ResponseEntity<ProblemDetail> handleTagDefinitionImmutable(
            TagDefinitionImmutableException ex, HttpServletRequest request) {
        return problem(UserMgmtErrorCode.TAG_DEFINITION_IMMUTABLE, ex.getMessage(), request);
    }

    // --- 422 domain-rule group → library subset code + app tag-definition code ---

    @ExceptionHandler(SubsetRuleViolationException.class)
    public ResponseEntity<ProblemDetail> handleSubsetRule(
            SubsetRuleViolationException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.ROLE_SUBSET_VIOLATION, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidTagDefinitionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTagDefinition(
            InvalidTagDefinitionException ex, HttpServletRequest request) {
        return problem(UserMgmtErrorCode.TAG_DEFINITION_INVALID, ex.getMessage(), request);
    }

    // --- 400 validation group → library VALIDATION_FAILED ---------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(
            IllegalArgumentException ex, HttpServletRequest request) {
        return problem(LibraryErrorCode.VALIDATION_FAILED, ex.getMessage(), request);
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
}
