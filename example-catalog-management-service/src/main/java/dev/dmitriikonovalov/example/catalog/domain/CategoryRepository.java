package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CategoryRepository
        extends JpaRepository<CategoryEntity, UUID>,
                JpaSpecificationExecutor<CategoryEntity>,
                LockableJpaRepository<CategoryEntity, UUID> {

    Optional<CategoryEntity> findByIdAndCatalogId(UUID id, UUID catalogId);
}
