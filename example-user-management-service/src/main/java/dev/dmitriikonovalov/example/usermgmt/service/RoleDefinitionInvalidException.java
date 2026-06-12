package dev.dmitriikonovalov.example.usermgmt.service;

/**
 * The submitted role definition violates the authoring contract (Phase 6.5): a {@code roleLevel}
 * outside the authorable ladder, a non-category permission token, a category beyond the level's
 * ceiling, or a denial that does not subtract from the granted expansion. Maps to
 * {@code 422 ROLE_DEFINITION_INVALID}.
 */
public class RoleDefinitionInvalidException extends RuntimeException {

    public RoleDefinitionInvalidException(String message) {
        super(message);
    }
}
