package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Extracts the authenticated {@link AbacContext.Subject} from an HTTP request.
 *
 * <p>Pluggable SPI: the default {@link JwtClaimsSubjectExtractor} reads a forwarded Bearer JWT, but a
 * deployment can supply its own (mTLS identity, a header set by a trusted gateway, a session) with a
 * single bean. Returns {@link Optional#empty()} when no usable identity is present — the caller then
 * proceeds anonymously and downstream authorization denies.
 */
@FunctionalInterface
public interface AbacSubjectExtractor {

    Optional<AbacContext.Subject> extract(HttpServletRequest request);
}
