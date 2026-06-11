package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository
        extends JpaRepository<ProductEntity, UUID>,
                LockableJpaRepository<ProductEntity, UUID> {

    Page<ProductEntity> findByCategoryId(UUID categoryId, Pageable pageable);

    Optional<ProductEntity> findByIdAndCategoryId(UUID id, UUID categoryId);
}
