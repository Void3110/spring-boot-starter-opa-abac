package dev.dmitriikonovalov.opaabac.data.model;

/**
 * The minimal contract every persistent domain model exposes: a stable identity and an
 * optimistic-lock version. {@link dev.dmitriikonovalov.opaabac.data.service.AbstractCrudService}
 * is generic over this, so any entity that satisfies it gets the safe CRUD + locking surface
 * without further coupling.
 *
 * @param <ID> the identity type (a plain {@link java.util.UUID} for the base entities here)
 */
public interface BaseModel<ID> {

    ID getId();

    Integer getVersion();
}
