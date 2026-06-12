package dev.dmitriikonovalov.opaabac.core;

/**
 * The version a decision was made on no longer matches the resource's current version — the decision
 * basis changed between the gate and the action (a detected TOCTOU race), or the resource carries no
 * version to guard against.
 *
 * <p>Thrown by {@link VersionGuard#requireUnchanged(Versioned, Versioned)}. Web adopters map it to
 * {@code 409} {@code STATE_CONFLICT} (problem+json): the client re-reads and retries, and the retry's
 * gate decides on the new state. The message names the resource reference and the two versions —
 * nothing else.
 */
public class VersionConflictException extends RuntimeException {

    /**
     * @param resource        the resource reference (e.g. {@code "category/«id»"})
     * @param expectedVersion the version the decision snapshot carried; may be {@code null}
     * @param actualVersion   the version the fresh load carried; may be {@code null}
     */
    public VersionConflictException(String resource, Integer expectedVersion, Integer actualVersion) {
        super("Resource " + resource + " version conflict: expected " + expectedVersion
                + ", found " + actualVersion);
    }
}
