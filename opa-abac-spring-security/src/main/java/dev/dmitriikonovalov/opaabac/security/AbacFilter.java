package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the {@link SecurityContextHolder} with an {@link AbacAuthentication} built from the
 * request, via the configured {@link AbacSubjectExtractor}.
 *
 * <p>On a resolvable subject it sets the authentication; on none it leaves the context anonymous. It
 * <strong>always continues the chain and never throws</strong> on a malformed token — a missing or bad
 * token simply means "anonymous", and downstream authorization then denies. It does not overwrite an
 * authentication another filter already established.
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

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Optional<AbacContext.Subject> subject = extractor.extract(request);
                subject.map(AbacAuthentication::new)
                        .ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
            } catch (RuntimeException e) {
                // Never let extraction break the request — leave the context anonymous.
                logger.debug("ABAC subject extraction failed; proceeding anonymously", e);
            }
        }
        filterChain.doFilter(request, response);
    }
}
