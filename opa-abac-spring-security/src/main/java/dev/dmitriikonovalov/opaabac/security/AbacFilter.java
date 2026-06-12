package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the {@link SecurityContextHolder} with an {@link AbacAuthentication} built from the
 * request, via the configured {@link AbacSubjectExtractor}.
 *
 * <p>On a resolvable subject it sets the authentication; on none it leaves the context as it was. It
 * <strong>always continues the chain and never throws</strong> on a malformed token — a missing or bad
 * token simply means "anonymous", and downstream authorization then denies. It does not overwrite a
 * <em>real</em> authentication another filter already established, but it does take precedence over an
 * anonymous one (so it can run after Spring Security's {@code AnonymousAuthenticationFilter}).
 */
public final class AbacFilter extends OncePerRequestFilter {

    private final AbacSubjectExtractor extractor;

    public AbacFilter(AbacSubjectExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (shouldAttemptExtraction()) {
            try {
                Optional<AbacContext.Subject> subject = extractor.extract(request);
                // A fresh context, swapped in whole — mutating the shared context in place would let
                // concurrent observers of the same instance see a half-initialized authentication.
                subject.map(AbacAuthentication::new).ifPresent(auth -> {
                    SecurityContext fresh = SecurityContextHolder.createEmptyContext();
                    fresh.setAuthentication(auth);
                    SecurityContextHolder.setContext(fresh);
                });
            } catch (RuntimeException e) {
                // Never let extraction break the request — leave the context as it was.
                logger.debug("ABAC subject extraction failed; proceeding anonymously", e);
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Extract when nobody is authenticated yet, or only an anonymous token is present. */
    private static boolean shouldAttemptExtraction() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current == null || current instanceof AnonymousAuthenticationToken;
    }
}
