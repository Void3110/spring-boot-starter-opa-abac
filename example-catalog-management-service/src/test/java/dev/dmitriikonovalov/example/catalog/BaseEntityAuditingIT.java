package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.example.catalog.support.PermissiveSecurityTestConfig;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Proves the base-entity adoption against real Postgres: the app boots under
 * {@code ddl-auto: validate} (so the entity mappings match the 0001+0002 schema), auditing
 * populates the audit columns, the optimistic-lock version advances on update, and JSONB tags
 * round-trip through the {@code tags} column.
 *
 * <p>Booting this {@code @SpringBootTest} at all is the schema-match proof (I1): if any mapping
 * disagreed with a column type, Hibernate's schema validation would fail startup.
 */
class BaseEntityAuditingIT extends AbstractPostgresIT {

    /** The principal the auditor should record — these tests persist directly (no HTTP filter), so we
     * set the same editor authentication the permissive web filter would. */
    private static final UUID EXPECTED_PRINCIPAL = PermissiveSecurityTestConfig.TEST_PRINCIPAL;

    @Autowired
    CatalogRepository catalogs;

    @Autowired
    CategoryRepository categories;

    @Autowired
    ProductRepository products;

    @BeforeEach
    void authenticate() {
        AbacContext.Subject subject = new AbacContext.Subject(
                EXPECTED_PRINCIPAL.toString(), List.of("catalog-editor"), Map.of("username", "it-editor"));
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(subject));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** Persist a catalog + category and return a (transient) product under that category. */
    private ProductEntity newProductUnderFreshCategory() {
        CatalogEntity catalog = catalogs.saveAndFlush(
                new CatalogEntity(UUID.randomUUID(), "Electronics", "All electronics"));
        CategoryEntity category = categories.saveAndFlush(
                new CategoryEntity(UUID.randomUUID(), catalog.getId(), null, "Laptops", null));
        return new ProductEntity(
                UUID.randomUUID(), category.getId(), "UltraBook 14",
                "A laptop", "UB-14", 129900L, "USD");
    }

    @Test
    void insertPopulatesAuditFieldsAndVersion() { // I3
        ProductEntity saved = products.saveAndFlush(newProductUnderFreshCategory());

        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo(EXPECTED_PRINCIPAL);
        assertThat(saved.getLastModifiedAt()).isNotNull();
        assertThat(saved.getTags().isEmpty()).isTrue();
    }

    @Test
    void updateBumpsVersionAndLastModifiedButKeepsCreated() { // I4
        ProductEntity saved = products.saveAndFlush(newProductUnderFreshCategory());
        var createdAt = saved.getCreatedAt();
        var createdBy = saved.getCreatedBy();

        saved.setName("UltraBook 14 Pro");
        ProductEntity updated = products.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);   // unchanged
        assertThat(updated.getCreatedBy()).isEqualTo(createdBy);   // unchanged
        assertThat(updated.getLastModifiedAt()).isNotNull();
    }

    @Test
    void jsonbTagsRoundTripThroughTheColumn() { // I5
        ProductEntity product = newProductUnderFreshCategory();
        product.setTags(ResourceTags.empty()
                .with("tier", "gold")
                .with("members", List.of("a", "b")));
        UUID id = products.saveAndFlush(product).getId();

        products.flush();
        ProductEntity reloaded = products.findById(id).orElseThrow();

        assertThat(reloaded.getTags().string("tier")).isEqualTo("gold");
        assertThat(reloaded.getTags().list("members")).containsExactly("a", "b");
    }
}
