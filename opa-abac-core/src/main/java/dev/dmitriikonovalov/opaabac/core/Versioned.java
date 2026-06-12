package dev.dmitriikonovalov.opaabac.core;

/**
 * A resource that exposes an optimistic-lock version, so an authorization decision made on one state
 * can be bound to the state a later action sees ({@link VersionGuard}).
 *
 * <p>One number, two consumers: the same version field that detects persistence races also detects
 * the gate-to-handler window — a decision snapshot whose version no longer matches the freshly loaded
 * resource means the decision basis changed and the action must not proceed.
 *
 * <p>A {@code null} version means the resource cannot be guarded (e.g. not yet persisted, or not
 * version-mapped); {@link VersionGuard} fails loud on it rather than silently passing.
 */
public interface Versioned {

    Integer getVersion();
}
