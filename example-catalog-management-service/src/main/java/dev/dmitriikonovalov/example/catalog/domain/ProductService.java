package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.service.AbstractCrudService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Product persistence service. Extends the library's {@link AbstractCrudService}, so it inherits the
 * safe-by-default write path: {@code mutate(id, fn)} locks the row for update, applies the change,
 * and saves in one transaction — concurrent writers of the same product serialize instead of racing
 * on a stale {@code @Version}.
 *
 * <p>{@code ProductRepository} extends {@code LockableJpaRepository}, so {@code getByIdForUpdate} /
 * {@code mutate} are available (they would throw a clear error otherwise).
 */
@Service
public class ProductService extends AbstractCrudService<ProductEntity, UUID> {

    public ProductService(ProductRepository repository) {
        super(repository);
    }
}
