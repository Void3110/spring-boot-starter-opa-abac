package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.data.jpa.domain.Specification;

/**
 * Test fixtures producing {@link AncestorResolver} stubs that vary only their {@link
 * AncestorResolver#ancestorsOf} behavior — the surface {@link HierarchicalAuthorizer} exercises. Since
 * {@code AncestorResolver} gained a second method ({@link AncestorResolver#subtreeOf}) it is no longer a
 * single-method functional interface, so these helpers replace the former one-line lambdas. Every fixture's
 * {@code subtreeOf} is the fail-closed empty predicate (matches no row) — single-resource tests never use it.
 */
final class TestAncestorResolvers {

    private TestAncestorResolvers() {}

    /** A resolver returning a fixed ancestor chain; {@code subtreeOf} is the fail-closed empty predicate. */
    static AncestorResolver ancestors(List<ParentRef> chain) {
        return new AncestorResolver() {
            @Override
            public List<ParentRef> ancestorsOf(String leafType, String leafId) {
                return chain;
            }

            @Override
            public <T> Specification<T> subtreeOf(String rootType, String rootId) {
                return (root, query, cb) -> cb.disjunction();
            }
        };
    }

    /** A resolver whose {@code ancestorsOf} throws the supplied exception (the fail-closed walk signal). */
    static AncestorResolver ancestorsThrowing(Supplier<? extends RuntimeException> exception) {
        return new AncestorResolver() {
            @Override
            public List<ParentRef> ancestorsOf(String leafType, String leafId) {
                throw exception.get();
            }

            @Override
            public <T> Specification<T> subtreeOf(String rootType, String rootId) {
                return (root, query, cb) -> cb.disjunction();
            }
        };
    }
}
