package dev.dmitriikonovalov.opaabac.data.service;

/**
 * Thrown by {@link AbstractCrudService} when an entity is required by id but does not exist
 * (e.g. {@code getById} / {@code getByIdForUpdate} / {@code mutate} on a missing id). A clear domain
 * signal so callers don't have to reason about a leaked empty {@code Optional}.
 *
 * <p>Unchecked so write paths stay clean; applications can map it to an HTTP 404 in one place.
 */
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String message) {
        super(message);
    }
}
