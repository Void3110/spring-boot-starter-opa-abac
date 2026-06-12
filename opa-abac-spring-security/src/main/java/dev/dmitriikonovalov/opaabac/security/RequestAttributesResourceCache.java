package dev.dmitriikonovalov.opaabac.security;

import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The default {@link AbacResourceCache}: entries live as request attributes
 * ({@link RequestContextHolder}), so they are naturally request-bounded with no scope proxying.
 *
 * <p><strong>No request context → a clean no-op:</strong> {@code get} returns empty and {@code put}
 * does nothing (never throws). Async or non-web callers simply lose the reuse, never the decision —
 * the manager resolves fresh regardless of this cache.
 */
public final class RequestAttributesResourceCache implements AbacResourceCache {

    private static final String KEY_PREFIX = AbacResourceCache.class.getName() + ":";

    @Override
    public <T> Optional<T> get(String resourceType, String resourceId, Class<T> as) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return Optional.empty();
        }
        Object value = request.getAttribute(key(resourceType, resourceId), RequestAttributes.SCOPE_REQUEST);
        return as.isInstance(value) ? Optional.of(as.cast(value)) : Optional.empty();
    }

    @Override
    public void put(String resourceType, String resourceId, Object resource) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null || resource == null) {
            return;
        }
        request.setAttribute(key(resourceType, resourceId), resource, RequestAttributes.SCOPE_REQUEST);
    }

    private static String key(String resourceType, String resourceId) {
        return KEY_PREFIX + resourceType + ":" + resourceId;
    }
}
