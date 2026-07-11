package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository
        extends JpaRepository<ProductEntity, UUID>,
                JpaSpecificationExecutor<ProductEntity>,
                LockableJpaRepository<ProductEntity, UUID> {

    Optional<ProductEntity> findByIdAndCategoryId(UUID id, UUID categoryId);
}
