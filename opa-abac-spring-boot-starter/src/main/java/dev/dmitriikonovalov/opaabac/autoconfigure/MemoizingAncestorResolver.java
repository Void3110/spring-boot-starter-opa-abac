package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolutionException;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * A request-scoped memoizing decorator over the application's {@link AncestorResolver} (ADR 0023):
 * within one web request, {@link #ancestorsOf} consults the delegate <strong>once</strong> per
 * {@code (leafType, leafId)} and replays its first outcome — the chain <em>or</em> the
 * {@link AncestorResolutionException} (re-thrown verbatim). This kills the measured per-list
 * double-resolve: the query path ({@code AbacQueryService.withResource}) and the enrichment path
 * ({@code ActionEnrichmentAdvice.prepareRow}) hit the same bean for the same rows, 2×N per page.
 *
 * <p>It lives in the <em>starter</em> (not {@code opa-abac-spring-data}, which has no spring-web and
 * must not gain it; not {@code opa-abac-spring-security}, which cannot see {@code AncestorResolver})
 * — the starter is the only module seeing both the SPI and the request-attributes storage
 * (ADR 0023 §1). Because the starter's {@code AncestorChainSupplier} bindings are method references
 * on the post-processed bean, one decorator covers the query path <em>and</em> the advice path;
 * each caller keeps its own degrade for the replayed throw (query → direct-grant-only; advice →
 * omit the row) — the memo replays outcomes, it never reinterprets them. Corollary: the filter and
 * the enrichment can never see two different chains for one row in one request.
 *
 * <p>{@link #subtreeOf} is a pure pass-through: it returns a lazily-evaluated JPA
 * {@code Specification} (already fail-closed to an always-false predicate internally), is called
 * once per list request, and is not part of the measured double-resolve.
 *
 * <p><strong>No request context → a clean pass-through</strong>, and the decorator never throws
 * from its own bookkeeping — the {@code RequestAttributesResourceCache} degrade language: callers
 * lose the reuse, never the decision. Staleness is bounded by the request (nothing survives it).
 * Wired bean-level by {@code OpaResolveMemoAutoConfiguration} under the same single flag as the
 * role memo ({@code opa.abac.resolve-memo.enabled}, default on — one knob, one axis).
 */
final class MemoizingAncestorResolver implements AncestorResolver {

    private static final String ATTRIBUTE = MemoizingAncestorResolver.class.getName() + ":memo";

    private final AncestorResolver delegate;

    MemoizingAncestorResolver(AncestorResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ParentRef> ancestorsOf(String leafType, String leafId) {
        Map<MemoKey, Object> memo = requestMemo();
        if (memo == null) {
            return delegate.ancestorsOf(leafType, leafId);
        }
        MemoKey key = new MemoKey(leafType, leafId);
        Object outcome = memo.get(key);
        if (outcome == null) {
            outcome = resolve(key);
            memo.put(key, outcome);
        }
        return replay(outcome);
    }

    @Override
    public <T> Specification<T> subtreeOf(String rootType, String rootId) {
        return delegate.subtreeOf(rootType, rootId);
    }

    /** One real delegate call; the outcome (chain or the contractual collapse) captured for replay. */
    private Object resolve(MemoKey key) {
        try {
            return delegate.ancestorsOf(key.leafType(), key.leafId());
        } catch (AncestorResolutionException collapse) {
            return new Collapse(collapse);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ParentRef> replay(Object outcome) {
        if (outcome instanceof Collapse collapse) {
            throw collapse.cause();
        }
        return (List<ParentRef>) outcome;
    }

    /**
     * The request's memo map, or {@code null} when there is no bound request (or the request
     * attributes refuse access): the pass-through signal. Never throws.
     */
    private static Map<MemoKey, Object> requestMemo() {
        try {
            RequestAttributes request = RequestContextHolder.getRequestAttributes();
            if (request == null) {
                return null;
            }
            Object existing = request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (existing instanceof Map<?, ?> map) {
                return uncheckedMap(map);
            }
            Map<MemoKey, Object> fresh = new ConcurrentHashMap<>();
            request.setAttribute(ATTRIBUTE, fresh, RequestAttributes.SCOPE_REQUEST);
            return fresh;
        } catch (RuntimeException bookkeepingFailure) {
            return null; // lose the reuse, never the decision
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<MemoKey, Object> uncheckedMap(Map<?, ?> map) {
        return (Map<MemoKey, Object>) map;
    }

    record MemoKey(String leafType, String leafId) {}

    /** The memoized failure state: the contractual chain-collapse, re-thrown verbatim on replay. */
    private record Collapse(AncestorResolutionException cause) {}
}
