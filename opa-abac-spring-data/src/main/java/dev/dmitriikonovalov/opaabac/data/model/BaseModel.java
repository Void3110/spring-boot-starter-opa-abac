package dev.dmitriikonovalov.opaabac.data.model;

import dev.dmitriikonovalov.opaabac.core.Versioned;

/**
 * The minimal contract every persistent domain model exposes: a stable identity and an
 * optimistic-lock version. {@link dev.dmitriikonovalov.opaabac.data.service.AbstractCrudService}
 * is generic over this, so any entity that satisfies it gets the safe CRUD + locking surface
 * without further coupling. Extending {@link Versioned} is a pure hierarchy statement — the same
 * version that detects persistence races also binds gate decisions to handler state
 * ({@code VersionGuard}).
 *
 * @param <ID> the identity type (a plain {@link java.util.UUID} for the base entities here)
 */
public interface BaseModel<ID> extends Versioned {

    ID getId();

    Integer getVersion();
}
