package dev.dmitriikonovalov.opaabac.data.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit unit tests (no Spring, no DB) for both {@link AncestorResolver} impls, driven by in-memory
 * {@link LtreePathSource} / {@link ParentLinkSource} stubs. Pins the contract — <b>root-first, leaf-excluded,
 * fail-closed</b> — exhaustively across cycle / depth / broken / malformed (QA cases U5-adjacent + I2-I5
 * algorithm coverage; the real-Postgres equivalents live in {@code AncestorResolverIT}).
 */
class AncestorResolverTest {

    // A simple 3-level tree: catalog c → category k → product p (UUID-shaped ids so labels round-trip).
    private static final String CATALOG = "11111111-1111-1111-1111-111111111111";
    private static final String CATEGORY = "22222222-2222-2222-2222-222222222222";
    private static final String PRODUCT = "33333333-3333-3333-3333-333333333333";

    private static final ParentRef CATALOG_REF = new ParentRef("catalog", CATALOG);
    private static final ParentRef CATEGORY_REF = new ParentRef("category", CATEGORY);

    private static String path(ParentRef... refs) {
        StringBuilder sb = new StringBuilder();
        for (ParentRef ref : refs) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(HierarchyLabels.label(ref));
        }
        return sb.toString();
    }

    // --- ltree resolver -------------------------------------------------------

    @Test // ordering (root-first) + leaf-exclusion
    void ltree_returnsRootFirstLeafExcludedChain() {
        String leafPath = path(CATALOG_REF, CATEGORY_REF, new ParentRef("product", PRODUCT));
        AncestorResolver resolver = new LtreeAncestorResolver((t, id) -> Optional.of(leafPath), 32);

        assertThat(resolver.ancestorsOf("product", PRODUCT))
                .containsExactly(CATALOG_REF, CATEGORY_REF);
    }

    @Test // a root resource → empty chain (path is just its own label)
    void ltree_rootHasEmptyChain() {
        String rootPath = path(CATALOG_REF);
        AncestorResolver resolver = new LtreeAncestorResolver((t, id) -> Optional.of(rootPath), 32);
        assertThat(resolver.ancestorsOf("catalog", CATALOG)).isEmpty();
    }

    @Test // I4 — no path row → throw (broken lineage, not "no ancestors")
    void ltree_missingPathThrows() {
        AncestorResolver resolver = new LtreeAncestorResolver((t, id) -> Optional.empty(), 32);
        assertThatThrownBy(() -> resolver.ancestorsOf("product", PRODUCT))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("broken lineage");
    }

    @Test // I4 — NULL/blank path → throw
    void ltree_blankPathThrows() {
        AncestorResolver resolver = new LtreeAncestorResolver((t, id) -> Optional.of("  "), 32);
        assertThatThrownBy(() -> resolver.ancestorsOf("product", PRODUCT))
                .isInstanceOf(AncestorResolutionException.class);
    }

    @Test // I4 — malformed label → throw
    void ltree_malformedLabelThrows() {
        AncestorResolver resolver = new LtreeAncestorResolver((t, id) -> Optional.of("not-a-valid-label"), 32);
        assertThatThrownBy(() -> resolver.ancestorsOf("product", PRODUCT))
                .isInstanceOf(AncestorResolutionException.class);
    }

    @Test // the path's leaf must match the requested leaf (row consistency) → throw
    void ltree_leafMismatchThrows() {
        String otherLeaf = path(CATALOG_REF, new ParentRef("category", CATEGORY));
        AncestorResolver resolver = new LtreeAncestorResolver((t, id) -> Optional.of(otherLeaf), 32);
        assertThatThrownBy(() -> resolver.ancestorsOf("product", PRODUCT))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("does not match");
    }

    @Test // I3 — depth beyond maxDepth → throw, never truncate
    void ltree_depthBreachThrows() {
        String leafPath = path(CATALOG_REF, CATEGORY_REF, new ParentRef("product", PRODUCT));
        AncestorResolver resolver = new LtreeAncestorResolver((t, id) -> Optional.of(leafPath), 2); // 3 labels
        assertThatThrownBy(() -> resolver.ancestorsOf("product", PRODUCT))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("exceeds maxDepth");
    }

    @Test // a SQL/data-access error from the source fails closed (wrapped)
    void ltree_sourceErrorFailsClosed() {
        AncestorResolver resolver = new LtreeAncestorResolver((t, id) -> {
            throw new IllegalStateException("db down");
        }, 32);
        assertThatThrownBy(() -> resolver.ancestorsOf("product", PRODUCT))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("lookup failed");
    }

    // --- recursive-CTE resolver ----------------------------------------------

    @Test // ordering (root-first) + leaf-exclusion via live parent walk
    void cte_returnsRootFirstLeafExcludedChain() {
        Map<String, ParentRef> parents = new LinkedHashMap<>();
        parents.put("product:" + PRODUCT, CATEGORY_REF);
        parents.put("category:" + CATEGORY, CATALOG_REF);
        // catalog has no parent (root)
        AncestorResolver resolver = new RecursiveCteAncestorResolver(parentSource(parents), 32);

        assertThat(resolver.ancestorsOf("product", PRODUCT))
                .containsExactly(CATALOG_REF, CATEGORY_REF);
    }

    @Test // a root resource → empty chain
    void cte_rootHasEmptyChain() {
        AncestorResolver resolver = new RecursiveCteAncestorResolver((t, id) -> Optional.empty(), 32);
        assertThat(resolver.ancestorsOf("catalog", CATALOG)).isEmpty();
    }

    @Test // I2 — a cycle (A→B→A) → throw, never an infinite loop
    void cte_cycleThrows() {
        Map<String, ParentRef> parents = new LinkedHashMap<>();
        parents.put("category:a", new ParentRef("category", "b"));
        parents.put("category:b", new ParentRef("category", "a")); // back-edge
        AncestorResolver resolver = new RecursiveCteAncestorResolver(parentSource(parents), 32);

        assertThatThrownBy(() -> resolver.ancestorsOf("category", "a"))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("cycle");
    }

    @Test // a self-loop (A→A) → throw
    void cte_selfLoopThrows() {
        Map<String, ParentRef> parents = new LinkedHashMap<>();
        parents.put("category:a", new ParentRef("category", "a"));
        AncestorResolver resolver = new RecursiveCteAncestorResolver(parentSource(parents), 32);
        assertThatThrownBy(() -> resolver.ancestorsOf("category", "a"))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("cycle");
    }

    @Test // I3 — depth beyond maxDepth → throw
    void cte_depthBreachThrows() {
        Map<String, ParentRef> parents = new LinkedHashMap<>();
        parents.put("product:" + PRODUCT, CATEGORY_REF);
        parents.put("category:" + CATEGORY, CATALOG_REF);
        AncestorResolver resolver = new RecursiveCteAncestorResolver(parentSource(parents), 1); // needs 2 hops
        assertThatThrownBy(() -> resolver.ancestorsOf("product", PRODUCT))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("exceeded maxDepth");
    }

    @Test // a SQL/data-access error fails closed (wrapped)
    void cte_sourceErrorFailsClosed() {
        AncestorResolver resolver = new RecursiveCteAncestorResolver((t, id) -> {
            throw new IllegalStateException("db down");
        }, 32);
        assertThatThrownBy(() -> resolver.ancestorsOf("product", PRODUCT))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("lookup failed");
    }

    @Test // I1 (unit) — both impls agree on the same tree
    void bothImplsAgreeOnTheSameTree() {
        String leafPath = path(CATALOG_REF, CATEGORY_REF, new ParentRef("product", PRODUCT));
        AncestorResolver ltree = new LtreeAncestorResolver((t, id) -> Optional.of(leafPath), 32);

        Map<String, ParentRef> parents = new LinkedHashMap<>();
        parents.put("product:" + PRODUCT, CATEGORY_REF);
        parents.put("category:" + CATEGORY, CATALOG_REF);
        AncestorResolver cte = new RecursiveCteAncestorResolver(parentSource(parents), 32);

        assertThat(ltree.ancestorsOf("product", PRODUCT))
                .isEqualTo(cte.ancestorsOf("product", PRODUCT))
                .containsExactly(CATALOG_REF, CATEGORY_REF);
    }

    @Test // invalid maxDepth is rejected at construction
    void invalidMaxDepthRejected() {
        assertThatThrownBy(() -> new LtreeAncestorResolver((t, id) -> Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecursiveCteAncestorResolver((t, id) -> Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ParentLinkSource parentSource(Map<String, ParentRef> parents) {
        return (type, id) -> Optional.ofNullable(parents.get(type + ":" + id));
    }
}
