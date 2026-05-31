package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogRepository
        extends JpaRepository<CatalogEntity, UUID>,
                LockableJpaRepository<CatalogEntity, UUID> {
}
