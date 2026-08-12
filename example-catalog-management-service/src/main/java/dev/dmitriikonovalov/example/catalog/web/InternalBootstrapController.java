package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.config.TagAssignmentService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.opaabac.data.model.AbstractSecuredEntity;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalog service's <b>internal bootstrap</b> surface — its first internal <em>write</em> endpoint
 * (the only internal surface it had was the read-only ownership resolve). Mounted under
 * {@code /internal/**} (permitted in {@code SecurityConfig}, in-network only, never gateway-fronted —
 * the gateway proxies {@code /api/v1/**} alone), mirroring the user-service's bootstrap posture.
 *
 * <p>It exists because of a shape the public API deliberately does not have: an <b>operator-managed</b>
 * tag key (ADR 0030 §3) — {@code env}, the production tier — has, by construction, <em>no</em> public
 * write path, so something in-network has to be the operator. This endpoint <b>is</b> that operator, and
 * it bypasses the operator-managed rejection by construction: it calls
 * {@link TagAssignmentService#validateAsOperator}, a distinct entry point that never runs the delta
 * check, rather than passing a flag that a public caller could conceivably reach.
 *
 * <p>The bypass is scoped to <em>who may write</em>, never to <em>what is legal</em>: values are
 * dictionary-validated exactly as on the public path, so an unknown key or an illegal enum value is a
 * 422 here too.
 */
@RestController
public class InternalBootstrapController {

    private final CatalogRepository catalogs;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final TagAssignmentService tagAssignment;

    public InternalBootstrapController(
            CatalogRepository catalogs,
            CategoryRepository categories,
            ProductRepository products,
            TagAssignmentService tagAssignment) {
        this.catalogs = catalogs;
        this.categories = categories;
        this.products = products;
        this.tagAssignment = tagAssignment;
    }

    /**
     * <b>Merge-upsert</b> a resource's tags as the operator: only the posted keys change, a posted
     * {@code null} removes its key, and every other key on the resource survives. A merge (not a replace)
     * because the operator writes one key on rows whose other keys the public flows own — a replace would
     * make the two paths fight over the same JSONB column.
     *
     * <p>Idempotent by construction: posting the same map twice converges to the same state, so a retry
     * after an ambiguous failure is safe.
     *
     * <p>Dictionary addressing is by the posted {@code (resourceType, resourceId)} <b>as-is</b>. For this
     * slice's only operator use — {@code env} on a catalog — root and self are the same resource, so the
     * governing team resolves. Writing a <em>team-scoped</em> key on a non-root resource is out of scope:
     * the key would not resolve and the write answers the ordinary 422.
     *
     * @return the resource's resulting full tag map (so a caller can assert the merge rather than re-read)
     */
    @PostMapping("/internal/bootstrap/resource-tags")
    @Transactional
    public ResponseEntity<Map<String, Object>> upsertResourceTags(
            @RequestBody UpsertResourceTags body) {
        UUID resourceId = parseId(body.resourceId());
        AbstractSecuredEntity entity = load(body.resourceType(), resourceId)
                .orElseThrow(() -> new NotFoundException(
                        "Resource not found: " + body.resourceType() + " " + body.resourceId()));

        Map<String, Object> posted = body.tags() == null ? Map.of() : body.tags();

        // Validate only the posted, non-null values: the merge changes nothing else, and re-validating
        // untouched keys would let an unrelated dictionary change (a since-deleted team key) fail an
        // operator write that does not touch it.
        Map<String, Object> assigned = new LinkedHashMap<>();
        posted.forEach((key, value) -> {
            if (value != null) {
                assigned.put(key, value);
            }
        });
        Map<String, Object> validated = tagAssignment
                .validateAsOperator(body.resourceType(), body.resourceId(), assigned)
                .asMap();

        Map<String, Object> merged = new LinkedHashMap<>(entity.getTags().asMap());
        posted.forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, validated.get(key));
            }
        });

        // The entity was loaded inside this transaction, so it is managed: the tag change flushes on
        // commit through dirty checking — no repository-per-type save switch, and no cast back down.
        entity.setTags(ResourceTags.fromMap(merged));
        return ResponseEntity.ok(merged);
    }

    private static UUID parseId(String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException | NullPointerException _) {
            throw new NotFoundException("Resource not found: " + rawId);
        }
    }

    private Optional<AbstractSecuredEntity> load(String resourceType, UUID id) {
        return switch (resourceType == null ? "" : resourceType) {
            case "catalog" -> catalogs.findById(id).map(e -> e);
            case "category" -> categories.findById(id).map(e -> e);
            case "product" -> products.findById(id).map(e -> e);
            default -> Optional.empty();
        };
    }

    /**
     * @param resourceType {@code "catalog"} | {@code "category"} | {@code "product"}
     * @param resourceId   the resource's id (an unknown or unparseable id is a 404)
     * @param tags         the keys to merge; a {@code null} value removes its key
     */
    public record UpsertResourceTags(String resourceType, String resourceId, Map<String, Object> tags) {
    }
}
