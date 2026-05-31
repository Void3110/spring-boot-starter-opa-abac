package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests for {@link AbacFilter} — QA cases U19, U20. */
class AbacFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    @Test // U19 — valid token populates the context with an AbacAuthentication
    void validSubject_populatesContext() throws Exception {
        AbacContext.Subject subject =
                new AbacContext.Subject("user-1", List.of("catalog-viewer"), Map.of("username", "alice"));
        AbacFilter filter = new AbacFilter(req -> Optional.of(subject));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(AbacAuthentication.class);
        assertThat(((AbacAuthentication) auth).getSubject().id()).isEqualTo("user-1");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_catalog-viewer");
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test // U20a — no subject leaves the context anonymous, chain still continues
    void noSubject_anonymousAndChainContinues() throws Exception {
        AbacFilter filter = new AbacFilter(req -> Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test // U20b — an extractor that throws does not break the request
    void extractorThrows_doesNotBreakChain() throws Exception {
        AbacFilter filter = new AbacFilter(req -> {
            throw new IllegalStateException("boom");
        });

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }
}
