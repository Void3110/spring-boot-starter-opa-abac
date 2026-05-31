package dev.dmitriikonovalov.example.catalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {

    List<ProductEntity> findByCategoryId(UUID categoryId);

    Optional<ProductEntity> findByIdAndCategoryId(UUID id, UUID categoryId);
}
