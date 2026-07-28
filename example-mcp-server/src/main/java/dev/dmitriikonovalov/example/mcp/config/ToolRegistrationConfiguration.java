package dev.dmitriikonovalov.example.mcp.config;

import dev.dmitriikonovalov.example.mcp.tool.CatalogTools;
import dev.dmitriikonovalov.example.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.example.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.example.mcp.tool.ToolRegistryValidator;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The static authorization declarations for the tool surface.
 *
 * <p>Every tool names an action verb and the permission category that verb expands from — both drawn from
 * the <strong>shipped</strong> vocabulary in {@code infra/opa/policies/permission_categories.json}, so the
 * tool-gate derives a principal's ceiling through the same expansion the rest of the repo uses rather than
 * inventing a parallel one.
 *
 * <h2>Risk tags</h2>
 * The demo scale is {@code low} &lt; {@code medium} &lt; {@code high}. The <em>ordering</em> deliberately
 * lives in the policy (T3), not here: an agent capability's max risk tag is compared against a tool's tags
 * in Rego, where the comparison is auditable and {@code opa test}-able. A Java-side ordering would be a
 * second source of truth free to drift from the one that actually decides.
 *
 * <p>{@code get_product} is the {@code medium}-risk read — a product carries commercial detail the others
 * do not. That is what gives the slice its allow/deny contrast without gating a mutation: an agent capped
 * at {@code low} may browse the catalog structure and still be denied the product record, while its
 * principal — who is permitted both — is unaffected.
 */
@Configuration(proxyBeanMethods = false)
public class ToolRegistrationConfiguration {

    private static final String CATEGORY_READ = "READ";
    private static final String ACTION_LIST = "list";
    private static final String ACTION_VIEW = "view";
    private static final String RISK_LOW = "low";
    private static final String RISK_MEDIUM = "medium";

    @Bean
    ToolRegistry toolRegistry() {
        return new ToolRegistry(List.of(
                new ToolDescriptor(
                        CatalogTools.LIST_CATALOGS, ACTION_LIST, CATEGORY_READ, Set.of(RISK_LOW)),
                new ToolDescriptor(
                        CatalogTools.GET_CATALOG, ACTION_VIEW, CATEGORY_READ, Set.of(RISK_LOW)),
                new ToolDescriptor(
                        CatalogTools.LIST_CATEGORIES, ACTION_LIST, CATEGORY_READ, Set.of(RISK_LOW)),
                new ToolDescriptor(
                        CatalogTools.GET_PRODUCT, ACTION_VIEW, CATEGORY_READ, Set.of(RISK_MEDIUM))));
    }

    /**
     * The {@link ApplicationContext} is injected as the {@code ListableBeanFactory} the validator scans:
     * only {@code BeanFactory} and {@code ApplicationContext} are registered as resolvable dependencies,
     * and the context is the one of those that can enumerate bean definitions.
     */
    @Bean
    ToolRegistryValidator toolRegistryValidator(
            ApplicationContext applicationContext, ToolRegistry toolRegistry) {
        return new ToolRegistryValidator(applicationContext, toolRegistry);
    }
}
