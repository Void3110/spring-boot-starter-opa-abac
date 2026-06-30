package dev.dmitriikonovalov.example.usermgmt.service;

import java.util.UUID;

/**
 * Thrown when a caller tries to create a team governing a resource they do <strong>not</strong> own —
 * the target-squatting guard (Slice B4, ADR 0019). Mapped to <strong>403</strong> (library
 * {@code ACCESS_DENIED}) by the API exception handler, the same code an {@code @OpaPreAuthorize} deny
 * produces, so the squat-deny is indistinguishable from any other authorization failure to a client.
 *
 * <p><b>Fail-closed message hygiene:</b> the message names only the target type/id (already in the
 * caller's request), never the actual owner — a non-owner must not learn who the owner is.
 */
public class NotResourceOwnerException extends RuntimeException {

    public NotResourceOwnerException(String targetType, UUID targetId) {
        super("Caller does not own " + targetType + " " + targetId);
    }
}
