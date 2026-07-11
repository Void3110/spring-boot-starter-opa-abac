package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.ProductListAuthorizer;
import dev.dmitriikonovalov.example.catalog.config.TagAssignmentService;
import dev.dmitriikonovalov.example.catalog.config.TagDecisionGate;
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
import java.util.Map;
import java.util.Objects;
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
    private final TagAssignmentService tagAssignment;
    private final ProductListAuthorizer productListAuthorizer;
    private final CatalogHierarchyService hierarchy;
    private final ObjectProvider<AbacResourceCache> resourceCache;
    private final TagDecisionGate tagDecisionGate;

    public ProductController(ProductRepository products, ProductService productService,
                             CategoryRepository categories, TagAssignmentService tagAssignment,
                             ProductListAuthorizer productListAuthorizer,
                             CatalogHierarchyService hierarchy,
                             ObjectProvider<AbacResourceCache> resourceCache,
                             TagDecisionGate tagDecisionGate) {
        this.products = products;
        this.productService = productService;
        this.categories = categories;
        this.tagAssignment = tagAssignment;
        this.productListAuthorizer = productListAuthorizer;
        this.hierarchy = hierarchy;
        this.resourceCache = resourceCache;
        this.tagDecisionGate = tagDecisionGate;
    }

    @Override
    @OpaPreAuthorize(action = "product:list", resourceType = "'product'",
            roleResourceType = "'catalog'", roleResourceId = "#catalogId")
    public ResponseEntity<ProductPage> listProducts(
            UUID catalogId, UUID categoryId, Integer page, Integer perPage) {
        requireCategory(catalogId, categoryId);
        // Products carry tags now, so rows HAVE policy variance — the pre-tags plain repository page
        // would show a tag-gated role rows it may not read one-by-one. The which-rows cut happens in
        // SQL (partial-eval residual AND-ed with the categoryId scope), exactly as for categories;
        // the filtered path's survivors seed the request-scoped cache, so `_actions` enrichment keeps
        // working without the manual per-row seeding the plain page needed.
        var result = productListAuthorizer.readable(
                catalogId, categoryId, PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(CatalogMapper.toProductPage(result));
    }

    @Override
    @OpaPreAuthorize(action = "product:create", resourceType = "'product'",
            roleResourceType = "'catalog'", roleResourceId = "#catalogId")
    public ResponseEntity<Product> createProduct(UUID catalogId, UUID categoryId, ProductRequest request) {
        requireCategory(catalogId, categoryId);
        // Tag-on-create (Phase 6.5): a request that CARRIES tags needs the TYPE-LEVEL assign-tags
        // decision on top of the static create gate above (no instance exists yet to resolve).
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            tagDecisionGate.requireProductAssignTagsForCreate(catalogId);
        }
        var entity = new ProductEntity(
                UUID.randomUUID(),
                categoryId,
                request.getName(),
                request.getDescription(),
                request.getSku(),
                request.getPriceCents(),
                request.getCurrency());
        // Validate + assign tags against the dictionary before persisting, addressed by the GOVERNING
        // ROOT (the catalog — the team target), the remote call outside the create transaction —
        // see createCategory for the full rationale (fail-closed: 422 illegal / 503 fetch-failure).
        entity.setTags(tagAssignment.validateAndBuild(
                "catalog", catalogId.toString(), request.getTags()));
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

    /**
     * <b>No static annotation</b> (Phase 6.5, pinned semantic #2 — extended to products now that
     * their requests carry tags): authorization is the delta-aware dispatch below — a static
     * {@code product:update} could never let a TAG-without-WRITE role relabel tags, nor stop it from
     * editing content. Every decision still runs through the manager seam (the {@link TagDecisionGate}
     * methods carry the annotations) and precedes any mutation.
     */
    @Override
    public ResponseEntity<Product> updateProduct(UUID catalogId, UUID categoryId, UUID productId, ProductRequest request) {
        // Scope the product to its category/catalog (404 if it doesn't belong) before deciding.
        requireCategory(catalogId, categoryId);
        var current = requireProduct(categoryId, productId);
        // The deltas decide which authorization question(s) to ask. Tags compare RAW request map vs
        // the entity's current tags (null = empty; clearing tags IS a tags change) — raw-side compare
        // can only over-ask (more authz = narrower), never under-ask. Content = any non-tag field.
        boolean tagsDelta = !Objects.equals(
                request.getTags() == null ? Map.of() : request.getTags(),
                current.getTags().asMap());
        boolean contentDelta = !Objects.equals(current.getName(), request.getName())
                || !Objects.equals(current.getDescription(), request.getDescription())
                || !Objects.equals(current.getSku(), request.getSku())
                || !Objects.equals(current.getPriceCents(), request.getPriceCents())
                || !Objects.equals(current.getCurrency(), request.getCurrency());
        // Dispatch: content → update; tags → assign-tags; both → both (update first); an EMPTY delta
        // → update (the conservative default — a no-op PUT by a TAG-only holder answers 403).
        if (contentDelta || !tagsDelta) {
            tagDecisionGate.requireProductUpdate(productId);
        }
        if (tagsDelta) {
            tagDecisionGate.requireProductAssignTags(productId);
        }
        // Bind the deltas' basis to the gate's: the dispatched decisions resolved their own snapshot
        // (5.97 write-through) — if it isn't the row the deltas were computed on, a racer won the
        // window between our load and the gate, and the dispatch may have asked the wrong question.
        guardGateSnapshot(current);
        // Tag validation calls the tag-definition service (slow, fail-closed) — before, never inside,
        // the locked transaction below (and AFTER authorization, so an unauthorized caller learns
        // nothing from the 422 vocabulary). Addressed by the governing root (see createProduct).
        var tags = tagAssignment.validateAndBuild(
                "catalog", catalogId.toString(), request.getTags());
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
            entity.setTags(tags);
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
