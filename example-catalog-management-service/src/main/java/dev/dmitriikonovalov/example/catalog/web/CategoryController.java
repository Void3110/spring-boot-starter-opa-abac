package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.CategoryAuthorizer;
import dev.dmitriikonovalov.example.catalog.config.CategoryListAuthorizer;
import dev.dmitriikonovalov.example.catalog.config.TagAssignmentService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.openapi.api.CategoryApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Category;
import dev.dmitriikonovalov.example.catalog.openapi.model.CategoryPage;
import dev.dmitriikonovalov.example.catalog.openapi.model.CategoryRequest;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class CategoryController implements CategoryApi {

    private final CategoryRepository categories;
    private final CatalogRepository catalogs;
    private final TagAssignmentService tagAssignment;
    private final CategoryAuthorizer categoryAuthorizer;
    private final CategoryListAuthorizer categoryListAuthorizer;
    private final CatalogHierarchyService hierarchy;

    public CategoryController(
            CategoryRepository categories,
            CatalogRepository catalogs,
            TagAssignmentService tagAssignment,
            CategoryAuthorizer categoryAuthorizer,
            CategoryListAuthorizer categoryListAuthorizer,
            CatalogHierarchyService hierarchy) {
        this.categories = categories;
        this.catalogs = catalogs;
        this.tagAssignment = tagAssignment;
        this.categoryAuthorizer = categoryAuthorizer;
        this.categoryListAuthorizer = categoryListAuthorizer;
        this.hierarchy = hierarchy;
    }

    @Override
    @OpaPreAuthorize(action = "category:read", resourceType = "'category'")
    public ResponseEntity<CategoryPage> listCategories(
            UUID catalogId, UUID parentId, Integer page, Integer perPage) {
        requireCatalog(catalogId);
        // The @OpaPreAuthorize above is the coarse type-level gate ("may read categories at all", layer 2).
        // The which-rows cut happens in SQL here (layer 3): partial-eval residual AND-ed with the catalog
        // (+ parent) path scope. A subject with no role definition gets an empty list, not the full table.
        // The page windows the FILTERED set, so count is the caller's authorized total.
        var result = categoryListAuthorizer.readable(
                catalogId, parentId, PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(CatalogMapper.toCategoryPage(result));
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
        UUID categoryId = UUID.randomUUID();
        var entity = new CategoryEntity(
                categoryId,
                catalogId,
                request.getParentId(),
                request.getName(),
                request.getDescription());
        // Validate + assign tags against the dictionary before persisting (fail-closed: an illegal tag
        // throws 422 and a definitions-fetch failure throws 503 — nothing is stored either way).
        entity.setTags(tagAssignment.validateAndBuild(
                "category", categoryId.toString(), request.getTags()));
        hierarchy.assignPath(entity); // path = parent (category or catalog) path || category_<id>
        var saved = categories.save(entity);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(CatalogMapper.toDto(saved));
    }

    @Override
    public ResponseEntity<Category> getCategory(UUID catalogId, UUID categoryId) {
        // Load-then-check: the Category's TAGS drive the decision, so we authorize the loaded instance
        // (its tags reach OPA), resolving the role via the governing Catalog. This is the per-instance,
        // tag-based grant — the pre-invocation @OpaPreAuthorize can't see the tags (Phase 4.5).
        var entity = requireCategory(catalogId, categoryId);
        categoryAuthorizer.require("read", entity);
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
        entity.setTags(tagAssignment.validateAndBuild(
                "category", categoryId.toString(), request.getTags()));
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
