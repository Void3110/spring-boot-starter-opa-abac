package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.openapi.api.CatalogApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Catalog;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogPage;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogRequest;
import dev.dmitriikonovalov.opaabac.core.VersionGuard;
import dev.dmitriikonovalov.opaabac.security.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class CatalogController implements CatalogApi {

    private final CatalogRepository catalogs;
    private final CatalogHierarchyService hierarchy;
    private final ObjectProvider<AbacResourceCache> resourceCache;

    public CatalogController(CatalogRepository catalogs, CatalogHierarchyService hierarchy,
                             ObjectProvider<AbacResourceCache> resourceCache) {
        this.catalogs = catalogs;
        this.hierarchy = hierarchy;
        this.resourceCache = resourceCache;
    }

    @Override
    @OpaPreAuthorize(action = "catalog:list", resourceType = "'catalog'")
    public ResponseEntity<CatalogPage> listCatalogs(Integer page, Integer perPage) {
        var result = catalogs.findAll(PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(CatalogMapper.toCatalogPage(result));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:create", resourceType = "'catalog'")
    public ResponseEntity<Catalog> createCatalog(CatalogRequest request) {
        var entity = new CatalogEntity(
                UUID.randomUUID(),
                request.getName(),
                request.getDescription());
        // A root (no parent to lock): path = catalog_<id>, derived + inserted in one transaction.
        var saved = hierarchy.createWithPath(entity, catalogs::save);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(CatalogMapper.toDto(saved));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:view", resourceType = "'catalog'", resourceId = "#catalogId")
    public ResponseEntity<Catalog> getCatalog(UUID catalogId) {
        // The response is the snapshot the gate authorized (Phase 5.97); repository fallback covers
        // resolution-off / non-web paths.
        var entity = cachedCatalog(catalogId)
                .orElseGet(() -> catalogs.findById(catalogId)
                        .orElseThrow(() -> new NotFoundException("Catalog not found: " + catalogId)));
        return ResponseEntity.ok(CatalogMapper.toDto(entity));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:update", resourceType = "'catalog'", resourceId = "#catalogId")
    public ResponseEntity<Catalog> updateCatalog(UUID catalogId, CatalogRequest request) {
        var entity = catalogs.findById(catalogId)
                .orElseThrow(() -> new NotFoundException("Catalog not found: " + catalogId));
        // Version binding (Phase 5.97): drift between the gate's snapshot and this fresh load → 409,
        // never a silent overwrite. Before any write; the snapshot is never persisted.
        guardGateSnapshot(entity);
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        return ResponseEntity.ok(CatalogMapper.toDto(catalogs.save(entity)));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:delete", resourceType = "'catalog'", resourceId = "#catalogId")
    public ResponseEntity<Void> deleteCatalog(UUID catalogId) {
        var entity = catalogs.findById(catalogId)
                .orElseThrow(() -> new NotFoundException("Catalog not found: " + catalogId));
        guardGateSnapshot(entity);
        catalogs.delete(entity);
        return ResponseEntity.noContent().build();
    }

    /** The gate's authorized snapshot for this request, when resolution populated the cache. */
    private Optional<CatalogEntity> cachedCatalog(UUID catalogId) {
        AbacResourceCache cache = resourceCache.getIfAvailable();
        return cache == null
                ? Optional.empty()
                : cache.get("catalog", catalogId.toString(), CatalogEntity.class);
    }

    /** Bind the gate's decision to this transaction's state (no snapshot → today's window, documented). */
    private void guardGateSnapshot(CatalogEntity fresh) {
        cachedCatalog(fresh.getId()).ifPresent(snapshot -> VersionGuard.requireUnchanged(snapshot, fresh));
    }
}
