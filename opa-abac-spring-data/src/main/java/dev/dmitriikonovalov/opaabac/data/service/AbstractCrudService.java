package dev.dmitriikonovalov.opaabac.data.service;

import dev.dmitriikonovalov.opaabac.data.model.BaseModel;
import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A generic CRUD service whose <strong>safe-under-concurrency write path is the easy default</strong>.
 *
 * <p>It exposes the two read styles explicitly, so the locking decision is always visible at the call
 * site rather than hidden:
 * <ul>
 *   <li>{@link #getById(Object)} — unlocked, read-only. For rendering/listing; never followed by a save.</li>
 *   <li>{@link #getByIdForUpdate(Object)} — {@code SELECT ... FOR UPDATE}. For a row you are about to
 *       mutate that other writers may touch concurrently.</li>
 * </ul>
 *
 * <p>{@link #mutate(Object, Consumer)} is the method to reach for first: it locks, applies the change,
 * and saves in one transaction, so two concurrent mutations of the same row serialize and neither
 * sees a stale {@code @Version}. See the concurrency guide for the rationale.
 *
 * <p>{@code getByIdForUpdate}/{@code mutate} are opt-in and <em>loud</em>: they require the injected
 * repository to also implement {@link LockableJpaRepository}, and throw a clear
 * {@link UnsupportedOperationException} otherwise — never a silent unlocked read.
 *
 * @param <MODEL> the entity type (must be a {@link BaseModel})
 * @param <ID>    the identity type
 */
public abstract class AbstractCrudService<MODEL extends BaseModel<ID>, ID> {

    private final JpaRepository<MODEL, ID> repository;
    private final String modelName;

    protected AbstractCrudService(JpaRepository<MODEL, ID> repository) {
        this.repository = repository;
        this.modelName = resolveModelName(getClass());
    }

    /** The backing repository, for subclasses that need entity-specific finder methods. */
    protected JpaRepository<MODEL, ID> repository() {
        return repository;
    }

    // --- Reads (no lock) ---

    /** Find by id without locking; empty if absent. Use when you are only reading. */
    @Transactional(readOnly = true)
    public Optional<MODEL> findById(ID id) {
        return repository.findById(id);
    }

    /** Get by id without locking; throws {@link EntityNotFoundException} if absent. */
    @Transactional(readOnly = true)
    public MODEL getById(ID id) {
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public List<MODEL> getAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public boolean exists(ID id) {
        return repository.existsById(id);
    }

    // --- Writes ---

    @Transactional
    public MODEL save(MODEL entity) {
        return repository.save(entity);
    }

    @Transactional
    public MODEL saveAndFlush(MODEL entity) {
        return repository.saveAndFlush(entity);
    }

    @Transactional
    public void remove(MODEL entity) {
        repository.delete(entity);
    }

    // --- Locked read + the safe-by-default mutation ---

    /**
     * Get by id with a pessimistic write lock ({@code SELECT ... FOR UPDATE}); throws
     * {@link EntityNotFoundException} if absent. Requires the repository to implement
     * {@link LockableJpaRepository}, else throws {@link UnsupportedOperationException}.
     */
    @Transactional
    public MODEL getByIdForUpdate(ID id) {
        return lockable().findByIdForUpdate(id).orElseThrow(() -> notFound(id));
    }

    /**
     * The safe-by-default write: lock the row for update, apply {@code fn}, and save — all in one
     * transaction, so concurrent mutations of the same row serialize.
     *
     * <p>Keep slow/external work (HTTP calls, heavy compute) <em>out</em> of {@code fn}: the lock is
     * held until the transaction commits, so the critical section should be just the read-modify-write.
     */
    @Transactional
    public MODEL mutate(ID id, Consumer<MODEL> fn) {
        MODEL entity = getByIdForUpdate(id);
        fn.accept(entity);
        return repository.save(entity);
    }

    @SuppressWarnings("unchecked")
    private LockableJpaRepository<MODEL, ID> lockable() {
        if (repository instanceof LockableJpaRepository<?, ?> lockable) {
            return (LockableJpaRepository<MODEL, ID>) lockable;
        }
        throw new UnsupportedOperationException(
                "Pessimistic locking requires the repository to implement LockableJpaRepository; "
                        + repository.getClass().getName() + " does not. Add it alongside JpaRepository "
                        + "to use getByIdForUpdate(id) / mutate(id, fn).");
    }

    private EntityNotFoundException notFound(ID id) {
        return new EntityNotFoundException(modelName + " not found: " + id);
    }

    /**
     * Best-effort entity name for not-found messages: read the {@code MODEL} type argument off the
     * concrete subclass (e.g. {@code ProductService extends AbstractCrudService<ProductEntity, UUID>}
     * → {@code "ProductEntity"}). Falls back to {@code "Entity"} if the hierarchy doesn't expose it
     * (e.g. an intermediate generic layer), so the id in the message is always the load-bearing part.
     */
    private static String resolveModelName(Class<?> serviceClass) {
        Class<?> current = serviceClass;
        while (current != null && current != Object.class) {
            Type superType = current.getGenericSuperclass();
            if (superType instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == AbstractCrudService.class) {
                Type modelType = parameterized.getActualTypeArguments()[0];
                if (modelType instanceof Class<?> modelClass) {
                    return modelClass.getSimpleName();
                }
                return "Entity";
            }
            current = current.getSuperclass();
        }
        return "Entity";
    }
}
