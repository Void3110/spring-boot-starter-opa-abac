package dev.dmitriikonovalov.example.catalog.domain;

import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CatalogRepository
        extends JpaRepository<CatalogEntity, UUID>,
                JpaSpecificationExecutor<CatalogEntity>,
                LockableJpaRepository<CatalogEntity, UUID> {
    // JpaSpecificationExecutor (Slice B4 T4) is required by AbacQueryService.findAuthorized — the
    // catalog list now filters through the governed base scope + the partial-eval residual, exactly as
    // CategoryRepository already does. Without it, CatalogListAuthorizer would not compile.
}
