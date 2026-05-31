package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository
        extends JpaRepository<CategoryEntity, UUID>,
                LockableJpaRepository<CategoryEntity, UUID> {

    List<CategoryEntity> findByCatalogId(UUID catalogId);

    List<CategoryEntity> findByCatalogIdAndParentId(UUID catalogId, UUID parentId);

    Optional<CategoryEntity> findByIdAndCatalogId(UUID id, UUID catalogId);
}
