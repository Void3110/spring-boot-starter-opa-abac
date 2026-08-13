package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.CategoryListAuthorizer;
import dev.dmitriikonovalov.example.catalog.config.TagAssignmentService;
import dev.dmitriikonovalov.example.catalog.config.TagDecisionGate;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.openapi.api.CategoryApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Category;
import dev.dmitriikonovalov.example.catalog.openapi.model.CategoryPage;
import dev.dmitriikonovalov.example.catalog.openapi.model.CategoryRequest;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.VersionGuard;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class CategoryController implements CategoryApi {

    private final CategoryRepository categories;
    private final CatalogRepository catalogs;
    private final TagAssignmentService tagAssignment;
    private final CategoryListAuthorizer categoryListAuthorizer;
    private final CatalogHierarchyService hierarchy;
    private final ObjectProvider<AbacResourceCache> resourceCache;
    private final TagDecisionGate tagDecisionGate;

    public CategoryController(
            CategoryRepository categories,
            CatalogRepository catalogs,
            TagAssignmentService tagAssignment,
            CategoryListAuthorizer categoryListAuthorizer,
            CatalogHierarchyService hierarchy,
            ObjectProvider<AbacResourceCache> resourceCache,
            TagDecisionGate tagDecisionGate) {
        this.categories = categories;
        this.catalogs = catalogs;
        this.tagAssignment = tagAssignment;
        this.categoryListAuthorizer = categoryListAuthorizer;
        this.hierarchy = hierarchy;
        this.resourceCache = resourceCache;
        this.tagDecisionGate = tagDecisionGate;
    }

    @Override
    @OpaPreAuthorize(action = "category:list", resourceType = "'category'",
            roleResourceType = "'catalog'", roleResourceId = "#catalogId")
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
    @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
            roleResourceType = "'catalog'", roleResourceId = "#catalogId")
    public ResponseEntity<Category> createCategory(UUID catalogId, CategoryRequest request) {
        requireCatalog(catalogId);
        // Tag-on-create (Phase 6.5): a request that CARRIES tags needs the TYPE-LEVEL assign-tags
        // decision on top of the static create gate above (no instance exists yet to resolve).
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            tagDecisionGate.requireCategoryAssignTagsForCreate(catalogId);
        }
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
        // throws 422 and a definitions-fetch failure throws 503 — nothing is stored either way). The
        // remote call stays OUTSIDE the create transaction below. The dictionary is addressed by the
        // GOVERNING ROOT (the catalog — the team-target), never the raw resource: the user-service's
        // target matcher is exact, so only the root resolves the team whose custom keys apply — the
        // same caller-resolves-the-root rule the effective-role fetch follows.
        entity.setTags(tagAssignment.validateAndBuild(
                "catalog", catalogId.toString(), request.getTags()));
        // Path derivation + INSERT in one transaction, parent row locked — a concurrent re-parent of
        // the parent cannot leave this child under a branch that no longer exists.
        var saved = hierarchy.createWithPath(entity, categories::save);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(CatalogMapper.toDto(saved));
    }

    @Override
    @OpaPreAuthorize(action = "category:view", resourceType = "'category'", resourceId = "#categoryId")
    public ResponseEntity<Category> getCategory(UUID catalogId, UUID categoryId) {
        // The gate resolved the instance and decided on its tags + ancestors (Phase 5.97) — the
        // load-then-check CategoryAuthorizer this handler used to call is gone. The response is the
        // authorized snapshot from the request cache; the repository fallback covers resolution-off /
        // non-web paths. The URL-scope rule stays HERE: the resolver loads by id alone, so an existing
        // category under the wrong catalog path is still this handler's 404, never a grant.
        var entity = cachedCategory(categoryId)
                .orElseGet(() -> categories.findById(categoryId)
                        .orElseThrow(() -> new NotFoundException("Category not found: " + categoryId)));
        if (!entity.getCatalogId().equals(catalogId)) {
            throw new NotFoundException("Category not found: " + categoryId);
        }
        return ResponseEntity.ok(CatalogMapper.toDto(entity));
    }

    /**
     * <b>No static annotation</b> (Phase 6.5, pinned semantic #2): authorization is the delta-aware
     * dispatch below — a static {@code category:update} could never let a TAG-without-WRITE role
     * relabel tags, nor stop it from editing content. Every decision still runs through the manager
     * seam (the {@link TagDecisionGate} methods carry the annotations) and precedes any mutation.
     */
    @Override
    public ResponseEntity<Category> updateCategory(UUID catalogId, UUID categoryId, CategoryRequest request) {
        var entity = requireCategory(catalogId, categoryId);
        // The deltas decide which authorization question(s) to ask. Tags compare RAW request map vs
        // the entity's current tags (null = empty; clearing tags IS a tags change) — raw-side compare
        // can only over-ask (more authz = narrower), never under-ask. Content = any non-tag field.
        boolean tagsDelta = !Objects.equals(
                request.getTags() == null ? java.util.Map.of() : request.getTags(),
                entity.getTags().asMap());
        boolean contentDelta = !Objects.equals(entity.getName(), request.getName())
                || !Objects.equals(entity.getDescription(), request.getDescription())
                || !Objects.equals(entity.getParentId(), request.getParentId());
        // Dispatch: content → update; tags → assign-tags; both → both (update first); an EMPTY delta
        // → update (the conservative default — a no-op PUT by a TAG-only holder answers 403).
        if (contentDelta || !tagsDelta) {
            tagDecisionGate.requireCategoryUpdate(categoryId);
        }
        if (tagsDelta) {
            tagDecisionGate.requireCategoryAssignTags(categoryId);
        }
        // Version binding (Phase 5.97): the freshly loaded row must still be the version the gate
        // authorized — drift means a parallel writer won the window, and the answer is 409 (retry
        // re-runs the gate on the new state), never a silent overwrite. Before any write.
        guardGateSnapshot(entity);
        // Tag validation calls the tag-definition service (slow, fail-closed) — it must run before,
        // never inside, the locked re-parent transaction below (and AFTER authorization, so an
        // unauthorized caller learns nothing from the 422 vocabulary). Addressed by the governing
        // root (see createCategory) so the team's custom keys resolve.
        var tags = tagAssignment.validateAndBuild(
                "catalog", catalogId.toString(), request.getTags(), entity.getTags().asMap());
        if (!Objects.equals(entity.getParentId(), request.getParentId())) {
            if (request.getParentId() != null) {
                // New parent must exist within the same catalog.
                categories.findByIdAndCatalogId(request.getParentId(), catalogId)
                        .orElseThrow(() -> new NotFoundException(
                                "Parent category not found in catalog: " + request.getParentId()));
            }
            // A parent change must rewrite the subtree's ltree paths (the authorization lineage)
            // atomically with the adjacency change — a bare setParentId+save would leave every
            // hierarchy decision under this subtree following the OLD branch (and skip the cycle guard).
            // The pre-dispatch entity is the decision snapshot: the deltas + gate decisions above were
            // computed on its version, and the locked row must still carry it (drift -> 409) — the
            // fresh re-read below would otherwise absorb a racer and the save would overwrite it.
            entity = hierarchy.reparentCategory(categoryId, request.getParentId(), entity);
        }
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setTags(tags);
        return ResponseEntity.ok(CatalogMapper.toDto(categories.save(entity)));
    }

    @Override
    @OpaPreAuthorize(action = "category:delete", resourceType = "'category'", resourceId = "#categoryId")
    public ResponseEntity<Void> deleteCategory(UUID catalogId, UUID categoryId) {
        var entity = requireCategory(catalogId, categoryId);
        guardGateSnapshot(entity);
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

    /** The gate's authorized snapshot for this request, when resolution populated the cache. */
    private Optional<CategoryEntity> cachedCategory(UUID categoryId) {
        AbacResourceCache cache = resourceCache.getIfAvailable();
        return cache == null
                ? Optional.empty()
                : cache.get("category", categoryId.toString(), CategoryEntity.class);
    }

    /**
     * Bind the gate's decision to this transaction's state: if the request cache holds the snapshot
     * the gate authorized, its version must match the fresh load. No snapshot (resolution off,
     * non-web) → today's load-then-check window, documented, never silent. The snapshot itself is
     * never persisted.
     */
    private void guardGateSnapshot(CategoryEntity fresh) {
        cachedCategory(fresh.getId()).ifPresent(snapshot -> VersionGuard.requireUnchanged(snapshot, fresh));
    }
}
