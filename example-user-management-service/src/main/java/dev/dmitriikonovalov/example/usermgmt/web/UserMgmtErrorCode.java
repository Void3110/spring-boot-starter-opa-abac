package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.opaabac.security.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * The user-service's own {@link ApiErrorCode} vocabulary — its domain failures a client would branch on
 * <em>within</em> a status, beyond the library's {@code LibraryErrorCode}.
 *
 * <p>The advice groups several exceptions per status; per ADR 0011 §4 (semantic granularity) each distinct,
 * client-actionable failure gets its own code rather than collapsing to one generic per status. The {@code 409}
 * conflict group splits into six codes (a duplicate team target, a membership conflict, a role-code clash, an
 * attempt to edit an immutable system role, a tag-key clash, an attempt to edit an immutable tag definition);
 * the {@code 422} group keeps the library {@code ROLE_SUBSET_VIOLATION} for the subset rule and adds one app
 * code for an invalid tag-definition shape. Generic failures (not-found, validation) reuse the library codes.
 *
 * <p>Each constant carries the {@link HttpStatus} it maps to, mirroring {@code LibraryErrorCode}, so the
 * advice resolves {@code (status, code)} from one source.
 */
public enum UserMgmtErrorCode implements ApiErrorCode {

    /** A team already exists for the requested (targetType, targetId) — owner-on-create uniqueness. */
    TEAM_TARGET_EXISTS(HttpStatus.CONFLICT, "Team target already exists"),

    /** The membership change conflicts with the current state (e.g. the user is already a member). */
    MEMBERSHIP_CONFLICT(HttpStatus.CONFLICT, "Membership conflict"),

    /** A role definition with the requested code already exists for this team. */
    ROLE_CODE_CONFLICT(HttpStatus.CONFLICT, "Role code conflict"),

    /** The targeted role is a system role and cannot be modified. */
    ROLE_IMMUTABLE(HttpStatus.CONFLICT, "Role is immutable"),

    /** A tag key with the requested name already exists for this scope. */
    TAG_KEY_CONFLICT(HttpStatus.CONFLICT, "Tag key conflict"),

    /** The targeted tag definition is immutable (e.g. a system key) and cannot be modified. */
    TAG_DEFINITION_IMMUTABLE(HttpStatus.CONFLICT, "Tag definition is immutable"),

    /** The submitted tag definition is structurally invalid (bad value type / cardinality / pattern). */
    TAG_DEFINITION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Tag definition is invalid"),

    /**
     * The submitted role definition violates the Phase-6.5 authoring contract (bad {@code roleLevel},
     * non-category token, category beyond the level ceiling, denial of a never-granted action).
     */
    ROLE_DEFINITION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Role definition is invalid"),

    /**
     * The submitted reporting edge is structurally illegal — a self-edge, or one that would close a
     * cycle in the reporting relation (SUPERVISED-SCOPE T1). Emitted only from the internal
     * {@code /internal/bootstrap/reporting-edges} fixture surface, which is deliberately absent from
     * {@code user-mgmt-api.yaml} (public-API-only); no documented endpoint can produce it.
     */
    REPORTING_EDGE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Reporting edge is invalid");

    private final HttpStatus status;
    private final String title;

    UserMgmtErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String title() {
        return title;
    }
}
