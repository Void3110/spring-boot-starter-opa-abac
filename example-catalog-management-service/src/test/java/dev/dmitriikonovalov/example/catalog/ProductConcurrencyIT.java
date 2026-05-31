package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductService;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The headline concurrency proof: two writers update the same product, with a latch forcing their
 * critical sections to overlap. The locked {@code mutate(id, fn)} path serializes them; the unlocked
 * read+save path reproduces the lost-update failure it prevents. Runs against real Postgres
 * (Testcontainers) — pessimistic-lock semantics don't reproduce on H2.
 */
class ProductConcurrencyIT extends AbstractPostgresIT {

    @Autowired
    ProductService productService;

    @Autowired
    ProductRepository products;

    @Autowired
    CategoryRepository categories;

    @Autowired
    CatalogRepository catalogs;

    private UUID seedProduct() {
        CatalogEntity catalog = catalogs.saveAndFlush(
                new CatalogEntity(UUID.randomUUID(), "Electronics", null));
        CategoryEntity category = categories.saveAndFlush(
                new CategoryEntity(UUID.randomUUID(), catalog.getId(), null, "Laptops", null));
        ProductEntity product = products.saveAndFlush(new ProductEntity(
                UUID.randomUUID(), category.getId(), "Original", null, "SKU", 1000L, "USD"));
        return product.getId();
    }

    @Test
    void twoConcurrentMutatesSerializeAndBothApply() throws Exception { // I6
        UUID id = seedProduct();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // A signals once it holds the row lock; B waits for it before attempting its own mutate, so
        // B is guaranteed to contend for the already-locked row (forced overlap, no sleeps).
        CountDownLatch aHoldsLock = new CountDownLatch(1);
        CountDownLatch bIsBlocking = new CountDownLatch(1);

        try {
            Future<?> a = pool.submit(() -> productService.mutate(id, p -> {
                aHoldsLock.countDown();
                // Hold the lock until B has had time to start blocking on it. B can't actually
                // acquire the row until this tx commits; this brief wait makes the contention real.
                await(bIsBlocking, 2, TimeUnit.SECONDS);
                p.setName("A-updated");
                p.setDescription("changed-by-A");
            }));

            Future<?> b = pool.submit(() -> {
                await(aHoldsLock, 5, TimeUnit.SECONDS); // ensure A locked first
                bIsBlocking.countDown();                // release A to commit; B now blocks on the lock
                productService.mutate(id, p -> {
                    p.setSku("B-SKU");                  // sees A's committed state (fresh version)
                });
            });

            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS); // must not throw a stale-version exception
        } finally {
            pool.shutdownNow();
        }

        ProductEntity finalState = products.findById(id).orElseThrow();
        // Both writers committed against fresh state: version advanced exactly twice and each
        // mutation is present (A's name + B's sku), proving they serialized rather than clobbering.
        assertThat(finalState.getVersion()).isEqualTo(2);
        assertThat(finalState.getName()).isEqualTo("A-updated");
        assertThat(finalState.getSku()).isEqualTo("B-SKU");
    }

    @Test
    void unlockedReadThenSaveReproducesTheStaleVersionFailure() throws Exception { // I7 (illustrative)
        UUID id = seedProduct();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // Both threads read the SAME stale snapshot (version 0) before either saves, then both try to
        // save — the second save loses the optimistic-lock race. This is exactly what mutate() prevents.
        CountDownLatch bothHaveRead = new CountDownLatch(2);
        AtomicReference<Throwable> caught = new AtomicReference<>();

        try {
            Runnable racer = () -> {
                try {
                    ProductEntity stale = products.findById(id).orElseThrow(); // unlocked read
                    bothHaveRead.countDown();
                    await(bothHaveRead, 5, TimeUnit.SECONDS); // both hold version 0 before any save
                    stale.setName("racer-" + Thread.currentThread().threadId());
                    products.saveAndFlush(stale);
                } catch (RuntimeException e) {
                    caught.compareAndSet(null, e);
                }
            };
            Future<?> r1 = pool.submit(racer);
            Future<?> r2 = pool.submit(racer);
            r1.get(15, TimeUnit.SECONDS);
            r2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // One writer's stale save is rejected by the @Version check — the lost-update we designed
        // mutate() to make impossible.
        assertThat(caught.get())
                .as("the unlocked read+save path must surface an optimistic-lock failure")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private static void await(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            if (!latch.await(timeout, unit)) {
                throw new IllegalStateException("Timed out waiting on latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting on latch", e);
        }
    }
}
