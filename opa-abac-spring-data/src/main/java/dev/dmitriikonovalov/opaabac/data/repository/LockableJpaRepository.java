package dev.dmitriikonovalov.opaabac.data.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

/**
 * A repository fragment that adds a pessimistic "select for update" read. A concrete repository
 * mixes it in alongside {@code JpaRepository}:
 *
 * <pre>{@code
 * public interface ProductRepository
 *         extends JpaRepository<ProductEntity, UUID>,
 *                 LockableJpaRepository<ProductEntity, UUID> { }
 * }</pre>
 *
 * <p>{@link #findByIdForUpdate(Object)} issues {@code SELECT ... FOR UPDATE}
 * ({@link LockModeType#PESSIMISTIC_WRITE}), so concurrent writers of the same row serialize: the
 * second blocks until the first commits, then proceeds against fresh state. The SpEL
 * {@code #{#entityName}} makes this one query serve <em>every</em> entity, so there is no per-entity
 * boilerplate.
 *
 * <p>{@code @NoRepositoryBean}: this is a fragment, never instantiated on its own.
 *
 * @param <MODEL> the entity type
 * @param <ID>    the entity's identity type
 */
@NoRepositoryBean
public interface LockableJpaRepository<MODEL, ID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM #{#entityName} e WHERE e.id = :id")
    Optional<MODEL> findByIdForUpdate(@Param("id") ID id);
}
