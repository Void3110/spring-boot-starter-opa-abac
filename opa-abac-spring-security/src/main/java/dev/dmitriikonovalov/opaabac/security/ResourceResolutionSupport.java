package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.AbacResourceResolver;
import dev.dmitriikonovalov.opaabac.core.AncestorChainSupplier;
import java.util.Objects;

/**
 * The resolution collaborators the {@link OpaPreAuthorizeAuthorizationManager} needs to make a full
 * per-instance decision: the app's {@link AbacResourceResolver}, an optional
 * {@link AncestorChainSupplier} (absent for flat resources — the chain is then always empty), and the
 * {@link AbacResourceCache} the manager write-through-populates on allow.
 *
 * <p>Constructed by the starter's auto-configuration when an app registers a resolver bean (and the
 * kill-switch is on); the manager itself only ever sees this composition — handing it {@code null}
 * support keeps the pre-resolution, reference-based behavior byte-identical.
 */
public record ResourceResolutionSupport(
        AbacResourceResolver resolver, AncestorChainSupplier ancestorChainSupplier, AbacResourceCache cache) {

    /**
     * @param resolver              the app's instance resolver; required
     * @param ancestorChainSupplier the ancestor chain source, or {@code null} for flat resources
     *                              (the accessor may then return {@code null} — the chain is empty)
     * @param cache                 the request-scoped cache the manager populates on allow; required
     */
    public ResourceResolutionSupport {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(cache, "cache");
    }
}
