package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.openapi.api.CategoryApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Category;
import dev.dmitriikonovalov.example.catalog.openapi.model.CategoryRequest;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CategoryController implements CategoryApi {

    private final CategoryRepository categories;
    private final CatalogRepository catalogs;

    public CategoryController(CategoryRepository categories, CatalogRepository catalogs) {
        this.categories = categories;
        this.catalogs = catalogs;
    }

    @Override
    @OpaPreAuthorize(action = "category:read", resourceType = "'category'")
    public ResponseEntity<List<Category>> listCategories(UUID catalogId, UUID parentId) {
        requireCatalog(catalogId);
        var entities = (parentId == null)
                ? categories.findByCatalogId(catalogId)
                : categories.findByCatalogIdAndParentId(catalogId, parentId);
        return ResponseEntity.ok(entities.stream().map(CatalogMapper::toDto).toList());
    }

    @Override
    @OpaPreAuthorize(action = "category:write", resourceType = "'category'")
    public ResponseEntity<Category> createCategory(UUID catalogId, CategoryRequest request) {
        requireCatalog(catalogId);
        if (request.getParentId() != null) {
            // Parent must exist within the same catalog.
            categories.findByIdAndCatalogId(request.getParentId(), catalogId)
                    .orElseThrow(() -> new NotFoundException(
                            "Parent category not found in catalog: " + request.getParentId()));
        }
        var entity = new CategoryEntity(
                UUID.randomUUID(),
                catalogId,
                request.getParentId(),
                request.getName(),
                request.getDescription());
        var saved = categories.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(CatalogMapper.toDto(saved));
    }

    @Override
    @OpaPreAuthorize(action = "category:read", resourceType = "'category'", resourceId = "#categoryId")
    public ResponseEntity<Category> getCategory(UUID catalogId, UUID categoryId) {
        var entity = requireCategory(catalogId, categoryId);
        return ResponseEntity.ok(CatalogMapper.toDto(entity));
    }

    @Override
    @OpaPreAuthorize(action = "category:write", resourceType = "'category'", resourceId = "#categoryId")
    public ResponseEntity<Category> updateCategory(UUID catalogId, UUID categoryId, CategoryRequest request) {
        var entity = requireCategory(catalogId, categoryId);
        if (request.getParentId() != null) {
            categories.findByIdAndCatalogId(request.getParentId(), catalogId)
                    .orElseThrow(() -> new NotFoundException(
                            "Parent category not found in catalog: " + request.getParentId()));
        }
        entity.setParentId(request.getParentId());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        return ResponseEntity.ok(CatalogMapper.toDto(categories.save(entity)));
    }

    @Override
    @OpaPreAuthorize(action = "category:write", resourceType = "'category'", resourceId = "#categoryId")
    public ResponseEntity<Void> deleteCategory(UUID catalogId, UUID categoryId) {
        var entity = requireCategory(catalogId, categoryId);
        categories.delete(entity);
        return ResponseEntity.noContent().build();
    }

    private void requireCatalog(UUID catalogId) {
        if (!catalogs.existsById(catalogId)) {
            throw new NotFoundException("Catalog not found: " + catalogId);
        }
    }

    private CategoryEntity requireCategory(UUID catalogId, UUID categoryId) {
        return categories.findByIdAndCatalogId(categoryId, catalogId)
                .orElseThrow(() -> new NotFoundException("Category not found: " + categoryId));
    }
}
