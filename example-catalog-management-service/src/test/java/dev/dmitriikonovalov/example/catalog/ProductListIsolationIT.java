package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.ProductListAuthorizer;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.TagMatchMode;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Product-list isolation ITs against <b>real Postgres</b> (taggable products, deep-review fix): the
 * partial-eval residual is a real JSONB predicate pushed into SQL, so two subjects whose residuals
 * accept different tag values see <b>different product rows</b> from the same endpoint-shaped query;
 * a deny-all residual yields the empty page (never the whole table); and the residual is <b>AND-ed
 * with the categoryId scope, never replacing it</b> — a tag-matching product in a sibling category
 * stays invisible even under an all-accepting residual.
 *
 * <p>OPA is a programmable in-process stub (the {@link CatalogListIsolationIT} idiom): {@code compile}
 * returns a per-test residual — a {@code tags.region == <value>} condition, {@code ALLOW_ALL}, or
 * {@code DENY_ALL}. The role supplied is <b>tag-gated</b> ({@code requiredTags} non-empty), which also
 * pins that such a role never takes the subtree-widening path (widening would OR rows back in and
 * defeat the cut).
 */
@Import(ProductListIsolationIT.IsolationTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "catalog.role-source=none")
class ProductListIsolationIT extends AbstractPostgresIT {

    private static final String ALICE = "sub-alice"; // residual: region == emea
    private static final String BOB = "sub-bob";     // residual: region == apac

    @Autowired private ProductListAuthorizer authorizer;
    @Autowired private CatalogRepository catalogs;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private CatalogHierarchyService hierarchy;

    private final Pageable pageable =
            PageRequest.of(0, 50, Sort.by("createdAt").ascending().and(Sort.by("id")));

    private UUID catalogId;
    private UUID categoryId;
    private UUID siblingCategoryId;
    private UUID emeaProduct;
    private UUID apacProduct;
    private UUID untaggedProduct;
    private UUID siblingEmeaProduct;

    @BeforeEach
    void seed() {
        // Clean via the ROOT table only (the CatalogListIsolationIT idiom): deleteAll() removes rows
        // one-by-one with a version check, and category's self-FK cascades a parent's children away
        // mid-iteration — deleting the already-cascade-deleted child then throws
        // ObjectOptimisticLockingFailure when a prior suite left a parent-child tree in the shared
        // container. Deleting catalogs lets the DB cascades clear categories/products instead.
        catalogs.deleteAll();
        StubOpaClient.residual = PartialResult.allowAll();

        var catalog = new CatalogEntity(UUID.randomUUID(), "Iso Co", null);
        hierarchy.assignPath(catalog);
        catalogId = catalogs.save(catalog).getId();

        categoryId = saveCategory("primary-cat");
        siblingCategoryId = saveCategory("sibling-cat");

        emeaProduct = saveProduct(categoryId, "emea-prod", Map.of("region", "emea"));
        apacProduct = saveProduct(categoryId, "apac-prod", Map.of("region", "apac"));
        untaggedProduct = saveProduct(categoryId, "untagged-prod", Map.of());
        // The scope pin: tag-wise this row matches Alice's residual, but it lives in the SIBLING
        // category — the categoryId scope must keep it out no matter what the residual accepts.
        siblingEmeaProduct = saveProduct(siblingCategoryId, "sibling-emea-prod", Map.of("region", "emea"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test // two subjects with different tag residuals see DIFFERENT product rows (the cut is real SQL)
    void differentResidualsSeeDifferentRows() {
        StubOpaClient.residual = regionResidual("emea");
        Page<ProductEntity> aliceList = listAs(ALICE);
        StubOpaClient.residual = regionResidual("apac");
        Page<ProductEntity> bobList = listAs(BOB);

        assertThat(ids(aliceList)).containsExactly(emeaProduct);
        assertThat(ids(bobList)).containsExactly(apacProduct);
        assertThat(ids(aliceList)).doesNotContainAnyElementsOf(ids(bobList)); // disjoint — no leak
        // The untagged row matches neither residual; counts are the authorized totals.
        assertThat(aliceList.getTotalElements()).isEqualTo(1);
        assertThat(bobList.getTotalElements()).isEqualTo(1);
    }

    @Test // a deny-all residual → empty page (fail-closed), never the whole table
    void denyAllResidualYieldsEmptyPage() {
        StubOpaClient.residual = PartialResult.denyAll();
        Page<ProductEntity> page = listAs(ALICE);
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        // Sanity: rows exist — the empty page is the cut, not an empty DB.
        assertThat(products.count()).isEqualTo(4);
    }

    @Test // AND-not-replace: even an ALLOW_ALL residual stays inside the categoryId scope
    void allowAllResidualStaysInsideCategoryScope() {
        StubOpaClient.residual = PartialResult.allowAll();
        Page<ProductEntity> page = listAs(ALICE);
        assertThat(ids(page)).containsExactlyInAnyOrder(emeaProduct, apacProduct, untaggedProduct);
        assertThat(ids(page)).doesNotContain(siblingEmeaProduct);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test // the scope pin under a MATCHING residual: the sibling category's emea row stays invisible
    void residualNeverEscapesTheCategoryScope() {
        StubOpaClient.residual = regionResidual("emea");
        Page<ProductEntity> page = listAs(ALICE);
        assertThat(ids(page)).containsExactly(emeaProduct);
        assertThat(ids(page)).doesNotContain(siblingEmeaProduct); // tag matches; scope excludes
    }

    // --- helpers --------------------------------------------------------------------------------

    private static PartialResult regionResidual(String region) {
        return PartialResult.conditional(List.of(new Conjunction(
                List.of(new Condition("tags.region", Condition.Operator.EQ, region)))));
    }

    private Page<ProductEntity> listAs(String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new AbacAuthentication(new AbacContext.Subject(subject, List.of(), Map.of())));
        return authorizer.readable(catalogId, categoryId, pageable);
    }

    private static List<UUID> ids(Page<ProductEntity> page) {
        return page.getContent().stream().map(ProductEntity::getId).toList();
    }

    private UUID saveCategory(String name) {
        var entity = new CategoryEntity(UUID.randomUUID(), catalogId, null, name, null);
        hierarchy.assignPath(entity);
        return categories.save(entity).getId();
    }

    private UUID saveProduct(UUID category, String name, Map<String, Object> tags) {
        var entity = new ProductEntity(UUID.randomUUID(), category, name, null, "SKU-1", 100L, "USD");
        if (!tags.isEmpty()) {
            entity.setTags(ResourceTags.fromMap(tags));
        }
        hierarchy.assignPath(entity);
        return products.save(entity).getId();
    }

    // --- test doubles ---------------------------------------------------------------------------

    @TestConfiguration
    static class IsolationTestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        OpaClient stubOpaClient() {
            // @Primary so it wins over PermissiveSecurityTestConfig's allowAllOpaClient — the test
            // must CONTROL the compile residual (tag condition / allow-all / deny-all).
            return new StubOpaClient();
        }

        @Bean
        RoleDefinitionSupplier productListRoleSupplier() {
            // TAG-GATED on purpose: requiredTags non-empty pins the no-widening branch (an over-wide
            // subtreeSpec would OR rows back in); the StubOpaClient's residual decides the cut.
            return (userId, type, id) -> Optional.of(new RoleDefinition(
                    "regional-reader",
                    Map.of(),
                    Map.of("product", List.of("READ")),
                    Map.of("region", List.of("emea", "apac")),
                    TagMatchMode.ANY_OF));
        }
    }

    /** {@code compile} → the per-test residual; single decisions and batches stay permissive. */
    static final class StubOpaClient implements OpaClient {
        static volatile PartialResult residual = PartialResult.allowAll();

        @Override
        public boolean allow(AbacContext context) {
            return true;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return residual;
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(c -> true).toList();
        }
    }
}
