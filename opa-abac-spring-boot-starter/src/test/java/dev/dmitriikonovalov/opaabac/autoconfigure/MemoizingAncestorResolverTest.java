package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolutionException;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Unit tests for {@link MemoizingAncestorResolver} (ADR 0023; QA case U7): the chain is resolved
 * once per {@code (type, id)} per request, a memoized {@link AncestorResolutionException}
 * re-throws on replay with <em>each caller keeping its own degrade</em> (query path →
 * direct-grant-only; advice path → omit the row), nothing survives a request, no-request is pure
 * pass-through, and {@code subtreeOf} is never memoized.
 */
class MemoizingAncestorResolverTest {

    private static final List<ParentRef> CHAIN = List.of(new ParentRef("catalog", "cat-1"));

    private final CountingResolver delegate = new CountingResolver();
    private final MemoizingAncestorResolver memo = new MemoizingAncestorResolver(delegate);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test // U7 — the chain is resolved once per (type,id) per request; distinct keys don't collide
    void memoizesChainPerKey() {
        bindRequest();
        delegate.chain("category", "c-1", CHAIN);
        delegate.chain("category", "c-2", List.of());

        assertThat(memo.ancestorsOf("category", "c-1")).isEqualTo(CHAIN); // query path (withResource)
        assertThat(memo.ancestorsOf("category", "c-1")).isEqualTo(CHAIN); // advice path (prepareRow)
        assertThat(memo.ancestorsOf("category", "c-2")).isEmpty();

        assertThat(delegate.calls("category", "c-1")).isEqualTo(1);
        assertThat(delegate.calls("category", "c-2")).isEqualTo(1);
    }

    @Test // U7 — a memoized collapse RE-THROWS on replay; each caller applies its own degrade
    void memoizedCollapseReplaysForBothCallers() {
        bindRequest();
        AncestorResolutionException collapse = new AncestorResolutionException("broken lineage");
        delegate.failing("category", "c-1", collapse);

        // The query-path caller: catches the throw itself and degrades to direct-grant-only.
        List<ParentRef> queryPathChain;
        try {
            queryPathChain = memo.ancestorsOf("category", "c-1");
        } catch (AncestorResolutionException e) {
            assertThat(e).isSameAs(collapse);
            queryPathChain = List.of(); // direct-only: no ancestors supplied to the decision
        }
        assertThat(queryPathChain).isEmpty();

        // The advice-path caller: sees the SAME replayed throw and omits the row.
        boolean rowOmitted = false;
        try {
            memo.ancestorsOf("category", "c-1");
        } catch (AncestorResolutionException e) {
            assertThat(e).isSameAs(collapse);
            rowOmitted = true;
        }
        assertThat(rowOmitted).isTrue();

        // One real resolution attempt served both callers — the 2×N double-resolve is gone.
        assertThat(delegate.calls("category", "c-1")).isEqualTo(1);
    }

    @Test // U7 — the filter and the enrichment can never see two different chains in one request
    void resolverFlipYieldsOneChainPerRequest() {
        bindRequest();
        delegate.chain("category", "c-1", CHAIN);
        assertThat(memo.ancestorsOf("category", "c-1")).isEqualTo(CHAIN);

        delegate.chain("category", "c-1", List.of()); // a mid-request re-parent lands…
        assertThat(memo.ancestorsOf("category", "c-1")).isEqualTo(CHAIN);

        bindRequest(); // …at the next request boundary
        assertThat(memo.ancestorsOf("category", "c-1")).isEmpty();
    }

    @Test // nothing survives a request
    void nothingSurvivesTheRequest() {
        bindRequest();
        delegate.chain("category", "c-1", CHAIN);
        memo.ancestorsOf("category", "c-1");

        bindRequest();
        memo.ancestorsOf("category", "c-1");

        assertThat(delegate.calls("category", "c-1")).isEqualTo(2);
    }

    @Test // no request bound: pure pass-through, incl. the throw, nothing memoized
    void noRequestIsPurePassThrough() {
        RequestContextHolder.resetRequestAttributes();
        AncestorResolutionException collapse = new AncestorResolutionException("down");
        delegate.failing("category", "c-1", collapse);

        assertThatThrownBy(() -> memo.ancestorsOf("category", "c-1")).isSameAs(collapse);
        assertThatThrownBy(() -> memo.ancestorsOf("category", "c-1")).isSameAs(collapse);
        assertThat(delegate.calls("category", "c-1")).isEqualTo(2);
    }

    @Test // subtreeOf is a pass-through, never memoized (a lazy Specification, not a resolve)
    void subtreeOfIsNeverMemoized() {
        bindRequest();

        assertThat(memo.<Object>subtreeOf("catalog", "cat-1")).isNotNull();
        assertThat(memo.<Object>subtreeOf("catalog", "cat-1")).isNotNull();

        assertThat(delegate.subtreeCalls).isEqualTo(2);
    }

    /** A scriptable counting fake: one chain or one failure per key; calls counted per key. */
    private static final class CountingResolver implements AncestorResolver {

        private final Map<String, List<ParentRef>> chains = new HashMap<>();
        private final Map<String, AncestorResolutionException> failures = new HashMap<>();
        private final Map<String, Integer> counts = new HashMap<>();
        private int subtreeCalls;

        void chain(String type, String id, List<ParentRef> chain) {
            chains.put(type + "|" + id, chain);
            failures.remove(type + "|" + id);
        }

        void failing(String type, String id, AncestorResolutionException failure) {
            failures.put(type + "|" + id, failure);
        }

        int calls(String type, String id) {
            return counts.getOrDefault(type + "|" + id, 0);
        }

        @Override
        public List<ParentRef> ancestorsOf(String leafType, String leafId) {
            String key = leafType + "|" + leafId;
            counts.merge(key, 1, Integer::sum);
            AncestorResolutionException failure = failures.get(key);
            if (failure != null) {
                throw failure;
            }
            List<ParentRef> chain = chains.get(key);
            if (chain == null) {
                throw new AssertionError("unscripted ancestorsOf: " + key);
            }
            return chain;
        }

        @Override
        public <T> Specification<T> subtreeOf(String rootType, String rootId) {
            subtreeCalls++;
            return (root, query, cb) -> cb.disjunction();
        }
    }
}
