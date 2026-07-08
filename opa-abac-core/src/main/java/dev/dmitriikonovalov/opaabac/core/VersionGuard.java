package dev.dmitriikonovalov.opaabac.core;

import java.util.Objects;

/**
 * Binds an authorization decision to the state an action sees: the snapshot the decision was made on
 * must carry the same version as the freshly loaded resource, or the action must not proceed.
 *
 * <p>The intended call shape, in a mutating handler: load the resource fresh inside the transaction
 * (as always), then {@code VersionGuard.requireUnchanged(gateSnapshot, fresh)} <em>before any
 * write</em>. Drift means a parallel writer changed the resource between the gate's decision and this
 * transaction — the race is detected, not accepted. The snapshot itself is never persisted.
 *
 * <p><strong>A {@code null} version on either side throws too:</strong> guarding was requested, and an
 * unguardable resource must fail loud rather than silently pass (a silent pass would reopen the very
 * window the guard exists to close).
 */
public final class VersionGuard {

    private VersionGuard() {
    }

    /**
     * Require that the resource's version is unchanged since the decision snapshot was taken.
     *
     * @param snapshot the instance the decision was made on
     * @param current  the freshly loaded instance the action is about to act on
     * @throws VersionConflictException on version drift, or when either version is {@code null}
     */
    public static void requireUnchanged(Versioned snapshot, Versioned current) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(current, "current must not be null");
        Integer expected = snapshot.getVersion();
        Integer actual = current.getVersion();
        if (expected == null || actual == null || !expected.equals(actual)) {
            throw new VersionConflictException(describe(snapshot, current), expected, actual);
        }
    }

    private static String describe(Versioned snapshot, Versioned current) {
        Versioned source = snapshot instanceof AbacResource ? snapshot : current;
        if (source instanceof AbacResource abac) {
            return abac.abacResourceType() + "/" + abac.abacResourceId();
        }
        return source.getClass().getSimpleName();
    }
}
