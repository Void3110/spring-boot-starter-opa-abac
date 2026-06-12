package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the catalog app's Phase 5.5-A adoption end-to-end against <strong>real Postgres + the ltree
 * migration</strong> (the {@code AbstractPostgresIT} base runs the real Liquibase changelog, incl. 0003):
 * the {@code path} is derived on create, the wired {@link AncestorResolver} (ltree) decodes the full
 * {@code catalog → category → product} chain, and an atomic re-parent flips the resolved lineage.
 *
 * <p>This is the persistence/wiring proof; the through-the-gateway allow/deny + re-parent-flips-a-decision
 * proof is the newman e2e (T7).
 */
class HierarchyAdoptionIT extends AbstractPostgresIT {

    @Autowired
    private CatalogRepository catalogs;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private ProductRepository products;

    @Autowired
    private CatalogHierarchyService hierarchy;

    @Autowired
    private AncestorResolver ancestorResolver;

    @Autowired
    private PlatformTransactionManager txManager;

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    @Test
    @Transactional
    void insertDerivesPathAndResolverDecodesTheChain() {
        UUID catalogId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        CatalogEntity catalog = new CatalogEntity(catalogId, "Books", null);
        hierarchy.assignPath(catalog);
        catalogs.saveAndFlush(catalog);

        CategoryEntity category = new CategoryEntity(categoryId, catalogId, null, "Fiction", null);
        hierarchy.assignPath(category);
        categories.saveAndFlush(category);

        ProductEntity product =
                new ProductEntity(productId, categoryId, "Dune", null, "SKU-1", 1999L, "USD");
        hierarchy.assignPath(product);
        products.saveAndFlush(product);

        // Paths encode the full lineage.
        assertThat(catalog.getPath()).isEqualTo("catalog_" + hex(catalogId));
        assertThat(category.getPath())
                .isEqualTo("catalog_" + hex(catalogId) + ".category_" + hex(categoryId));
        assertThat(product.getPath())
                .isEqualTo("catalog_" + hex(catalogId)
                        + ".category_" + hex(categoryId)
                        + ".product_" + hex(productId));

        // The wired ltree resolver decodes the product's ancestor chain, root-first, leaf-excluded.
        assertThat(ancestorResolver.ancestorsOf("product", productId.toString()))
                .containsExactly(
                        new ParentRef("catalog", catalogId.toString()),
                        new ParentRef("category", categoryId.toString()));
    }

    @Test
    void reparentUnderSiblingCategoryFlipsLineage() {
        UUID catId = UUID.randomUUID();
        UUID parentA = UUID.randomUUID();
        UUID parentB = UUID.randomUUID();
        UUID childCategory = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        saveCatalog(catId, "Cat");
        saveCategory(parentA, catId, null);
        saveCategory(parentB, catId, null);
        saveCategory(childCategory, catId, parentA); // child under parentA
        saveProduct(productId, childCategory);

        // Before: the product's chain is catalog → parentA → childCategory.
        assertThat(ancestorResolver.ancestorsOf("product", productId.toString()))
                .containsExactly(
                        new ParentRef("catalog", catId.toString()),
                        new ParentRef("category", parentA.toString()),
                        new ParentRef("category", childCategory.toString()));

        // Move childCategory (and its product) under parentB.
        CategoryEntity moved = hierarchy.reparentCategory(childCategory, parentB);
        // the moved category's own path now sits under parentB
        assertThat(moved.getPath()).contains(".category_" + hex(parentB) + ".");

        // After: the chain now goes through parentB; the product sees the new lineage.
        assertThat(ancestorResolver.ancestorsOf("product", productId.toString()))
                .containsExactly(
                        new ParentRef("catalog", catId.toString()),
                        new ParentRef("category", parentB.toString()),
                        new ParentRef("category", childCategory.toString()));
    }

    @Test // latch-based (guide Rule 6): create-under-moving-parent serializes on the parent row lock
    void createUnderMovingParentSerializesOnTheParentLock() throws Exception {
        UUID catId = UUID.randomUUID();
        UUID parentP = UUID.randomUUID();
        UUID parentQ = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        saveCatalog(catId, "Race");
        saveCategory(parentP, catId, null);
        saveCategory(parentQ, catId, null);

        CountDownLatch moved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // T1: move P under Q, then HOLD the transaction open (the FOR UPDATE locks on P + Q with it).
            Future<?> mover = pool.submit(() -> new TransactionTemplate(txManager)
                    .executeWithoutResult(status -> {
                        hierarchy.reparentCategory(parentP, parentQ);
                        moved.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("release latch timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }));
            assertThat(moved.await(10, TimeUnit.SECONDS)).isTrue();

            // T2: create a child under P. Without the parent lock this would read P's PRE-move path and
            // commit a child the move's subtree rewrite (already executed) can never sweep — a stale
            // lineage. With the lock it must BLOCK until the move commits.
            Future<CategoryEntity> creator = pool.submit(() -> hierarchy.createWithPath(
                    new CategoryEntity(childId, catId, parentP, "child-under-moving-parent", null),
                    categories::save));
            assertThatThrownBy(() -> creator.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            mover.get(10, TimeUnit.SECONDS);
            CategoryEntity child = creator.get(10, TimeUnit.SECONDS);

            // The child landed under P's NEW branch (through Q) — never the branch that no longer exists.
            assertThat(child.getPath())
                    .startsWith("catalog_" + hex(catId)
                            + ".category_" + hex(parentQ)
                            + ".category_" + hex(parentP))
                    .endsWith(".category_" + hex(childId));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // --- helpers (each its own committed unit so the resolver's separate reads see them) -------------

    private void saveCatalog(UUID id, String name) {
        CatalogEntity c = new CatalogEntity(id, name, null);
        hierarchy.assignPath(c);
        catalogs.saveAndFlush(c);
    }

    private void saveCategory(UUID id, UUID catalogId, UUID parentId) {
        CategoryEntity k = new CategoryEntity(id, catalogId, parentId, "k-" + id, null);
        hierarchy.assignPath(k);
        categories.saveAndFlush(k);
    }

    private void saveProduct(UUID id, UUID categoryId) {
        ProductEntity p = new ProductEntity(id, categoryId, "p-" + id, null, null, 100L, "USD");
        hierarchy.assignPath(p);
        products.saveAndFlush(p);
    }
}
