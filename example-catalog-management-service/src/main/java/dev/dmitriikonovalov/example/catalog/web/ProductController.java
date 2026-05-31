package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductService;
import dev.dmitriikonovalov.example.catalog.openapi.api.ProductApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Product;
import dev.dmitriikonovalov.example.catalog.openapi.model.ProductRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController implements ProductApi {

    private final ProductRepository products;
    private final ProductService productService;
    private final CategoryRepository categories;

    public ProductController(ProductRepository products, ProductService productService,
                             CategoryRepository categories) {
        this.products = products;
        this.productService = productService;
        this.categories = categories;
    }

    @Override
    public ResponseEntity<List<Product>> listProducts(UUID catalogId, UUID categoryId) {
        requireCategory(catalogId, categoryId);
        var result = products.findByCategoryId(categoryId).stream().map(CatalogMapper::toDto).toList();
        return ResponseEntity.ok(result);
    }

    @Override
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
        var saved = products.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(CatalogMapper.toDto(saved));
    }

    @Override
    public ResponseEntity<Product> getProduct(UUID catalogId, UUID categoryId, UUID productId) {
        requireCategory(catalogId, categoryId);
        var entity = requireProduct(categoryId, productId);
        return ResponseEntity.ok(CatalogMapper.toDto(entity));
    }

    @Override
    public ResponseEntity<Product> updateProduct(UUID catalogId, UUID categoryId, UUID productId, ProductRequest request) {
        // Scope the product to its category/catalog (404 if it doesn't belong) before mutating.
        requireCategory(catalogId, categoryId);
        requireProduct(categoryId, productId);
        // mutate() locks the row for update, applies the change, and saves in one transaction, so
        // concurrent updates of the same product serialize instead of racing on a stale @Version.
        var updated = productService.mutate(productId, entity -> {
            entity.setName(request.getName());
            entity.setDescription(request.getDescription());
            entity.setSku(request.getSku());
            entity.setPriceCents(request.getPriceCents());
            entity.setCurrency(request.getCurrency());
        });
        return ResponseEntity.ok(CatalogMapper.toDto(updated));
    }

    @Override
    public ResponseEntity<Void> deleteProduct(UUID catalogId, UUID categoryId, UUID productId) {
        requireCategory(catalogId, categoryId);
        var entity = requireProduct(categoryId, productId);
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
}
