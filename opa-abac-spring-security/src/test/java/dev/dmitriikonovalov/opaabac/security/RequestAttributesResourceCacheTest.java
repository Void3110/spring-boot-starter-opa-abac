package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Unit tests for {@link RequestAttributesResourceCache} (QA case U12, the cache half). */
class RequestAttributesResourceCacheTest {

    private final RequestAttributesResourceCache cache = new RequestAttributesResourceCache();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test // U12 — put/get round-trip inside a request context
    void roundTripInRequestContext() {
        bindRequest();
        cache.put("category", "c-1", "the-instance");

        assertThat(cache.get("category", "c-1", String.class)).contains("the-instance");
    }

    @Test // U12 — a non-matching Class yields empty, never a ClassCastException
    void nonMatchingTypeYieldsEmpty() {
        bindRequest();
        cache.put("category", "c-1", "a-string");

        assertThat(cache.get("category", "c-1", Integer.class)).isEmpty();
    }

    @Test // U12 — entries are keyed by (type, id): a different reference misses
    void missesOnDifferentReference() {
        bindRequest();
        cache.put("category", "c-1", "the-instance");

        assertThat(cache.get("category", "c-2", String.class)).isEmpty();
        assertThat(cache.get("product", "c-1", String.class)).isEmpty();
    }

    @Test // U12 — no request context → get empty, put a no-op, never a throw
    void noRequestContextIsCleanNoOp() {
        RequestContextHolder.resetRequestAttributes();

        assertThatCode(() -> cache.put("category", "c-1", "the-instance")).doesNotThrowAnyException();
        assertThat(cache.get("category", "c-1", String.class)).isEmpty();

        // and a later request does not see the dropped put
        bindRequest();
        assertThat(cache.get("category", "c-1", String.class)).isEmpty();
    }

    @Test // a null resource is dropped, not stored (get would misreport it anyway)
    void nullResourceIsDropped() {
        bindRequest();
        assertThatCode(() -> cache.put("category", "c-1", null)).doesNotThrowAnyException();
        assertThat(cache.get("category", "c-1", Object.class)).isEmpty();
    }
}
