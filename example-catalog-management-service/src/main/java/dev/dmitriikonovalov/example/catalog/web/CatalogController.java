package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.CatalogListAuthorizer;
import dev.dmitriikonovalov.example.catalog.config.IllegalTagAssignmentException;
import dev.dmitriikonovalov.example.catalog.config.TagAssignmentService;
import dev.dmitriikonovalov.example.catalog.config.TagDecisionGate;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.openapi.api.CatalogApi;
import dev.dmitriikonovalov.example.catalog.openapi.model.Catalog;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogPage;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogRequest;
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
public class CatalogController implements CatalogApi {

    private final CatalogRepository catalogs;
    private final CatalogHierarchyService hierarchy;
    private final CatalogListAuthorizer catalogListAuthorizer;
    private final ObjectProvider<AbacResourceCache> resourceCache;
    private final TagDecisionGate tagDecisionGate;
    private final TagAssignmentService tagAssignment;

    public CatalogController(CatalogRepository catalogs, CatalogHierarchyService hierarchy,
                             CatalogListAuthorizer catalogListAuthorizer,
                             ObjectProvider<AbacResourceCache> resourceCache,
                             TagDecisionGate tagDecisionGate, TagAssignmentService tagAssignment) {
        this.catalogs = catalogs;
        this.hierarchy = hierarchy;
        this.catalogListAuthorizer = catalogListAuthorizer;
        this.resourceCache = resourceCache;
        this.tagDecisionGate = tagDecisionGate;
        this.tagAssignment = tagAssignment;
    }

    @Override
    public ResponseEntity<CatalogPage> listCatalogs(Integer page, Integer perPage) {
        // Slice B4 (ADR 0018): NO coarse @OpaPreAuthorize(catalog:list) gate. A catalog list is type-level
        // (no resourceId) so no per-resource role resolves, and after B4 there is no realm fallback — such a
        // gate would deny every membership-driven caller. CatalogListAuthorizer is the SOLE authority: the
        // governed base scope (id IN the catalogs I govern via team membership) AND-ed with the role-def-only
        // `filter` residual, fail-closed to an empty page. A subject governing nothing sees [].
        var result = catalogListAuthorizer.readable(PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(CatalogMapper.toCatalogPage(result));
    }

    @Override
    @OpaPreAuthorize(action = "catalog:create", resourceType = "'catalog'")
    public ResponseEntity<Catalog> createCatalog(CatalogRequest request) {
        // No tag-on-create for catalogs (unlike categories): the type-level assign-tags decision
        // resolves through the governing team, and a new catalog HAS no team until the caller's
        // owner-on-create step binds one — the decision would deny for everyone. Rejected loudly
        // (422), never silently dropped; assignment starts at the first update.
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            throw new IllegalTagAssignmentException(
                    "A catalog cannot carry tags at creation — create it, bind its team, then assign"
                            + " tags via update");
        }
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

    /**
     * <b>No static annotation</b> (the category dispatch, mirrored — Phase 6.5 pinned semantic #2):
     * authorization is the delta-aware dispatch below, so a TAG-without-WRITE role can relabel the
     * catalog without being able to edit its content, and vice versa. Every decision still runs
     * through the manager seam (the {@link TagDecisionGate} methods carry the annotations) and
     * precedes any mutation.
     */
    @Override
    public ResponseEntity<Catalog> updateCatalog(UUID catalogId, CatalogRequest request) {
        var entity = catalogs.findById(catalogId)
                .orElseThrow(() -> new NotFoundException("Catalog not found: " + catalogId));
        // The deltas decide which authorization question(s) to ask — raw request map vs the entity's
        // current tags (null = empty; clearing tags IS a tags change). Raw-side compare can only
        // over-ask (more authz = narrower), never under-ask.
        boolean tagsDelta = !Objects.equals(
                request.getTags() == null ? java.util.Map.of() : request.getTags(),
                entity.getTags().asMap());
        boolean contentDelta = !Objects.equals(entity.getName(), request.getName())
                || !Objects.equals(entity.getDescription(), request.getDescription());
        // Dispatch: content → update; tags → assign-tags; both → both; an EMPTY delta → update (the
        // conservative default). NOTE the ADR 0022 interplay: the root-read exemption never reaches
        // here — update/assign-tags are mutations, so a tag-requiring role still needs the CATALOG's
        // own tags to match before it may touch it.
        if (contentDelta || !tagsDelta) {
            tagDecisionGate.requireCatalogUpdate(catalogId);
        }
        if (tagsDelta) {
            tagDecisionGate.requireCatalogAssignTags(catalogId);
        }
        // Version binding (Phase 5.97): drift between the gate's snapshot and this fresh load → 409,
        // never a silent overwrite. Before any write; the snapshot is never persisted.
        guardGateSnapshot(entity);
        // Dictionary validation (slow, fail-closed remote call) — after authorization, before the
        // write. The catalog IS the governing root, so it addresses the dictionary by itself.
        var tags = tagAssignment.validateAndBuild(
                "catalog", catalogId.toString(), request.getTags());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setTags(tags);
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
