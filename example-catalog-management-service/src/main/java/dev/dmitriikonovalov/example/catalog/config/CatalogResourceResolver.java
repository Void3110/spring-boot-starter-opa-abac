package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The app's one {@link AbacResourceResolver}: dispatches the gate's {@code (type, id)} reference to
 * the matching repository so the {@code @OpaPreAuthorize} decision is made on the instance's real
 * attributes (tags) and ancestors. Registering this bean is the whole opt-in — zero annotation
 * changes elsewhere.
 *
 * <p>It loads by id alone, deliberately: URL scoping (a category belonging to the path's catalog)
 * stays in the handler — a resolver that filtered by path scope would absorb routing semantics into
 * authorization. Unknown type, unparseable id, or a missing row → {@link Optional#empty()}, which
 * the gate treats as a deny (fail-closed).
 */
@Component
public class CatalogResourceResolver implements AbacResourceResolver {

    private final CatalogRepository catalogs;
    private final CategoryRepository categories;
    private final ProductRepository products;

    public CatalogResourceResolver(
            CatalogRepository catalogs, CategoryRepository categories, ProductRepository products) {
        this.catalogs = catalogs;
        this.categories = categories;
        this.products = products;
    }

    @Override
    public Optional<AbacResource> resolve(String resourceType, String resourceId) {
        UUID id;
        try {
            id = UUID.fromString(resourceId);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        return switch (resourceType) {
            case "catalog" -> catalogs.findById(id).map(e -> e);
            case "category" -> categories.findById(id).map(e -> e);
            case "product" -> products.findById(id).map(e -> e);
            default -> Optional.empty();
        };
    }
}
