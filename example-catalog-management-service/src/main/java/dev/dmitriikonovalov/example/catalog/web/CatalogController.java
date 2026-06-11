package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.openapi.api.CatalogApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Catalog;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogPage;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogRequest;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class CatalogController implements CatalogApi {

    private final CatalogRepository catalogs;
    private final CatalogHierarchyService hierarchy;

    public CatalogController(CatalogRepository catalogs, CatalogHierarchyService hierarchy) {
        this.catalogs = catalogs;
        this.hierarchy = hierarchy;
    }

    @Override
    @OpaPreAuthorize(action = "catalog:read", resourceType = "'catalog'")
    public ResponseEntity<CatalogPage> listCatalogs(Integer page, Integer perPage) {
        var result = catalogs.findAll(PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(CatalogMapper.toCatalogPage(result));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:write", resourceType = "'catalog'")
    public ResponseEntity<Catalog> createCatalog(CatalogRequest request) {
        var entity = new CatalogEntity(
                UUID.randomUUID(),
                request.getName(),
                request.getDescription());
        hierarchy.assignPath(entity); // a root: path = catalog_<id>
        var saved = catalogs.save(entity);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(CatalogMapper.toDto(saved));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:read", resourceType = "'catalog'", resourceId = "#catalogId")
    public ResponseEntity<Catalog> getCatalog(UUID catalogId) {
        var entity = catalogs.findById(catalogId)
                .orElseThrow(() -> new NotFoundException("Catalog not found: " + catalogId));
        return ResponseEntity.ok(CatalogMapper.toDto(entity));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:write", resourceType = "'catalog'", resourceId = "#catalogId")
    public ResponseEntity<Catalog> updateCatalog(UUID catalogId, CatalogRequest request) {
        var entity = catalogs.findById(catalogId)
                .orElseThrow(() -> new NotFoundException("Catalog not found: " + catalogId));
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        return ResponseEntity.ok(CatalogMapper.toDto(catalogs.save(entity)));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:write", resourceType = "'catalog'", resourceId = "#catalogId")
    public ResponseEntity<Void> deleteCatalog(UUID catalogId) {
        if (!catalogs.existsById(catalogId)) {
            throw new NotFoundException("Catalog not found: " + catalogId);
        }
        catalogs.deleteById(catalogId);
        return ResponseEntity.noContent().build();
    }
}
