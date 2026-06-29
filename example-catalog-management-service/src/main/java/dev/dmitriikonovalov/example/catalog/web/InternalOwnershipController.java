package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The <b>internal</b> ownership-read contract the cross-service ownership resolver calls in-network
 * (Slice B4, ADR 0019) — the catalog side of the standard
 * {@code GET /internal/{resourceType}/{resourceId}/created-by} shape.
 *
 * <p>A <b>pure data read</b>: it returns the catalog's creator (the {@code created_by} sub, populated by
 * the {@code AuditorAware}); the comparison to the caller is the resolver's job (so the cache key stays
 * subject-independent). {@code 200 {"createdBy":"<sub-uuid>"}} when the catalog exists,
 * {@code 404} when it does not (the resolver maps a {@code 404} to not-owner).
 *
 * <p><b>Internal-only.</b> Under {@code /internal/**} (permitted in {@code SecurityConfig}, isolated by the
 * network boundary) — <b>never</b> gateway-fronted (the gateway proxies only {@code /api/v1/**} + Keycloak;
 * T8). Direct exposure would leak a creator id. Hand-written (an internal contract, not the public API).
 */
@RestController
public class InternalOwnershipController {

    private final CatalogRepository catalogs;

    public InternalOwnershipController(CatalogRepository catalogs) {
        this.catalogs = catalogs;
    }

    @GetMapping("/internal/catalog/{id}/created-by")
    public ResponseEntity<CreatedByView> createdBy(@PathVariable("id") UUID id) {
        return catalogs.findById(id)
                .map(catalog -> ResponseEntity.ok(new CreatedByView(catalog.getCreatedBy())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The ownership-read body. {@code createdBy} may be {@code null} when the catalog was created by an
     * unauthenticated / non-UUID auditor (the {@code AuditorAware} leaves it null rather than guessing) —
     * the resolver treats a null/absent {@code createdBy} as not-owner, so a record (which serializes a
     * null field) is correct where {@code Map.of} (null-hostile) would not be.
     */
    public record CreatedByView(UUID createdBy) {}
}
