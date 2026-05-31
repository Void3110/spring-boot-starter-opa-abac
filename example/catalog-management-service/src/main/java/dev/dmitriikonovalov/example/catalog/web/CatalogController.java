package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.openapi.api.CatalogApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Catalog;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController implements CatalogApi {

    private final CatalogRepository catalogs;

    public CatalogController(CatalogRepository catalogs) {
        this.catalogs = catalogs;
    }

    @Override
    public ResponseEntity<List<Catalog>> listCatalogs() {
        var result = catalogs.findAll().stream().map(CatalogMapper::toDto).toList();
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<Catalog> createCatalog(CatalogRequest request) {
        var entity = new CatalogEntity(
                UUID.randomUUID(),
                request.getName(),
                request.getDescription(),
                OffsetDateTime.now());
        var saved = catalogs.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(CatalogMapper.toDto(saved));
    }

    @Override
    public ResponseEntity<Catalog> getCatalog(UUID catalogId) {
        var entity = catalogs.findById(catalogId)
                .orElseThrow(() -> new NotFoundException("Catalog not found: " + catalogId));
        return ResponseEntity.ok(CatalogMapper.toDto(entity));
    }

    @Override
    public ResponseEntity<Catalog> updateCatalog(UUID catalogId, CatalogRequest request) {
        var entity = catalogs.findById(catalogId)
                .orElseThrow(() -> new NotFoundException("Catalog not found: " + catalogId));
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        return ResponseEntity.ok(CatalogMapper.toDto(catalogs.save(entity)));
    }

    @Override
    public ResponseEntity<Void> deleteCatalog(UUID catalogId) {
        if (!catalogs.existsById(catalogId)) {
            throw new NotFoundException("Catalog not found: " + catalogId);
        }
        catalogs.deleteById(catalogId);
        return ResponseEntity.noContent().build();
    }
}
