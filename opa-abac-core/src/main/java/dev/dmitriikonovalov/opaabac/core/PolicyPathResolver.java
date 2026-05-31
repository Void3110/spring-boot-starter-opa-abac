package dev.dmitriikonovalov.opaabac.core;

/**
 * Resolves the OPA data-document path an {@link AbacContext} should be evaluated against.
 *
 * <p>The {@link HttpOpaClient} POSTs to {@code <baseUrl>/v1/data/<path>}; this SPI decides the
 * {@code <path>} part (no leading or trailing slash). Keeping it pluggable lets a deployment route by
 * resource type (the default), tenant, policy version, or action with a single-bean override.
 */
@FunctionalInterface
public interface PolicyPathResolver {

    /**
     * @param context the decision context
     * @return the OPA data path with no leading/trailing slash (e.g. {@code "catalog/product"})
     */
    String resolve(AbacContext context);
}
