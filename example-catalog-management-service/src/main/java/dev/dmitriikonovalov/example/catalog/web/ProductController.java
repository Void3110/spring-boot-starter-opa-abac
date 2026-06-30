package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductService;
import dev.dmitriikonovalov.example.catalog.openapi.api.ProductApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Product;
import dev.dmitriikonovalov.example.catalog.openapi.model.ProductPage;
import dev.dmitriikonovalov.example.catalog.openapi.model.ProductRequest;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.VersionGuard;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class ProductController implements ProductApi {

    private final ProductRepository products;
    private final ProductService productService;
    private final CategoryRepository categories;
    private final CatalogHierarchyService hierarchy;
    private final ObjectProvider<AbacResourceCache> resourceCache;

    public ProductController(ProductRepository products, ProductService productService,
                             CategoryRepository categories, CatalogHierarchyService hierarchy,
                             ObjectProvider<AbacResourceCache> resourceCache) {
        this.products = products;
        this.productService = productService;
        this.categories = categories;
        this.hierarchy = hierarchy;
        this.resourceCache = resourceCache;
    }

    @Override
    @OpaPreAuthorize(action = "product:list", resourceType = "'product'",
            roleResourceType = "'catalog'", roleResourceId = "#catalogId")
    public ResponseEntity<ProductPage> listProducts(
            UUID catalogId, UUID categoryId, Integer page, Integer perPage) {
        requireCategory(catalogId, categoryId);
        var result = products.findByCategoryId(categoryId, PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(CatalogMapper.toProductPage(result));
    }

    @Override
    @OpaPreAuthorize(action = "product:create", resourceType = "'product'",
            roleResourceType = "'catalog'", roleResourceId = "#catalogId")
    public ResponseEntity<Product> createProduct(UUID catalogId, UUID categoryId, ProductRequest request) {
        requireCategory(catalogId, categoryId);
        var entity = new ProductEntity(
                UUID.randomUUID(),
                categoryId,
                request.getName(),
                request.getDescription(),
                request.getSku(),
                request.getPriceCents(),
                request.getCurrency());
        // Path derivation (category path || product_<id>) + INSERT in one transaction, the parent
        // category row locked — see CatalogHierarchyService.createWithPath.
        var saved = hierarchy.createWithPath(entity, products::save);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(CatalogMapper.toDto(saved));
    }

    @Override
    @OpaPreAuthorize(action = "product:view", resourceType = "'product'", resourceId = "#productId")
    public ResponseEntity<Product> getProduct(UUID catalogId, UUID categoryId, UUID productId) {
        // URL-scope rule stays in the handler (the resolver loads by id alone); the response is the
        // snapshot the gate authorized (Phase 5.97), with a repository fallback for resolution-off.
        requireCategory(catalogId, categoryId);
        var entity = cachedProduct(productId)
                .filter(cached -> cached.getCategoryId().equals(categoryId))
                .orElseGet(() -> requireProduct(categoryId, productId));
        return ResponseEntity.ok(CatalogMapper.toDto(entity));
    }

    @Override
    @OpaPreAuthorize(action = "product:update", resourceType = "'product'", resourceId = "#productId")
    public ResponseEntity<Product> updateProduct(UUID catalogId, UUID categoryId, UUID productId, ProductRequest request) {
        // Scope the product to its category/catalog (404 if it doesn't belong) before mutating.
        requireCategory(catalogId, categoryId);
        requireProduct(categoryId, productId);
        // Version binding (Phase 5.97): the guard runs INSIDE mutate's locked transaction, against the
        // row it locked — the decision basis is checked under the same protection the write holds
        // (decide-under-protection). Drift → 409; the snapshot is never persisted.
        var snapshot = cachedProduct(productId);
        // mutate() locks the row for update, applies the change, and saves in one transaction, so
        // concurrent updates of the same product serialize instead of racing on a stale @Version.
        var updated = productService.mutate(productId, entity -> {
            snapshot.ifPresent(s -> VersionGuard.requireUnchanged(s, entity));
            entity.setName(request.getName());
            entity.setDescription(request.getDescription());
            entity.setSku(request.getSku());
            entity.setPriceCents(request.getPriceCents());
            entity.setCurrency(request.getCurrency());
        });
        return ResponseEntity.ok(CatalogMapper.toDto(updated));
    }

    @Override
    @OpaPreAuthorize(action = "product:delete", resourceType = "'product'", resourceId = "#productId")
    public ResponseEntity<Void> deleteProduct(UUID catalogId, UUID categoryId, UUID productId) {
        requireCategory(catalogId, categoryId);
        var entity = requireProduct(categoryId, productId);
        guardGateSnapshot(entity);
        products.delete(entity);
        return ResponseEntity.noContent().build();
    }

    private void requireCategory(UUID catalogId, UUID categoryId) {
        categories.findByIdAndCatalogId(categoryId, catalogId)
                .orElseThrow(() -> new NotFoundException("Category not found: " + categoryId));
    }

    private ProductEntity requireProduct(UUID categoryId, UUID productId) {
        return products.findByIdAndCategoryId(productId, categoryId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));
    }

    /** The gate's authorized snapshot for this request, when resolution populated the cache. */
    private Optional<ProductEntity> cachedProduct(UUID productId) {
        AbacResourceCache cache = resourceCache.getIfAvailable();
        return cache == null
                ? Optional.empty()
                : cache.get("product", productId.toString(), ProductEntity.class);
    }

    /** Bind the gate's decision to this transaction's state (no snapshot → today's window, documented). */
    private void guardGateSnapshot(ProductEntity fresh) {
        cachedProduct(fresh.getId()).ifPresent(snapshot -> VersionGuard.requireUnchanged(snapshot, fresh));
    }
}
