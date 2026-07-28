package dev.dmitriikonovalov.example.mcp.tool;

import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tool surface: four read-only tools over the existing catalog-management service.
 *
 * <p>Each method is a thin proxy. There is no persistence here, no shared database, and no re-implemented
 * authorization — the catalog service answers with the caller's own token and applies its own per-type
 * policies untouched. Everything this class adds is a <em>second</em> front door, which is precisely what
 * the tool-gate (T4) exists to close.
 *
 * <p>The tools are all reads by design: this slice gates no mutation, so the allow/deny contrast it
 * demonstrates comes from the declared category and risk tier rather than from a destructive call. The
 * authorization attributes for each tool are declared statically in
 * {@code ToolRegistrationConfiguration} and cross-checked against this surface at startup by
 * {@link ToolRegistryValidator} — a tool added here without a declaration fails the context.
 */
@Component
public class CatalogTools {

    /** Tool names, shared with the descriptor declarations so the two cannot drift silently. */
    public static final String LIST_CATALOGS = "list_catalogs";

    public static final String GET_CATALOG = "get_catalog";

    public static final String LIST_CATEGORIES = "list_categories";

    public static final String GET_PRODUCT = "get_product";

    private static final String CATALOGS_PATH = "/api/v1/catalogs";

    private final CatalogApiClient catalogApi;

    public CatalogTools(CatalogApiClient catalogApi) {
        this.catalogApi = catalogApi;
    }

    @McpTool(
            name = LIST_CATALOGS,
            description = "List the product catalogs the calling user may see.",
            generateOutputSchema = false)
    public Map<String, Object> listCatalogs() {
        return catalogApi.getJson(CATALOGS_PATH);
    }

    @McpTool(
            name = GET_CATALOG,
            description = "Get one product catalog by id.",
            generateOutputSchema = false)
    public Map<String, Object> getCatalog(
            @McpToolParam(description = "The catalog id", required = true) String catalogId) {
        return catalogApi.getJson(catalogPath(catalogId));
    }

    @McpTool(
            name = LIST_CATEGORIES,
            description = "List the categories of one product catalog.",
            generateOutputSchema = false)
    public Map<String, Object> listCategories(
            @McpToolParam(description = "The catalog id", required = true) String catalogId) {
        return catalogApi.getJson(catalogPath(catalogId) + "/categories");
    }

    @McpTool(
            name = GET_PRODUCT,
            description = "Get one product, including its commercial detail.",
            generateOutputSchema = false)
    public Map<String, Object> getProduct(
            @McpToolParam(description = "The catalog id", required = true) String catalogId,
            @McpToolParam(description = "The category id", required = true) String categoryId,
            @McpToolParam(description = "The product id", required = true) String productId) {
        return catalogApi.getJson(catalogPath(catalogId)
                + "/categories/" + CatalogApiClient.segment(categoryId)
                + "/products/" + CatalogApiClient.segment(productId));
    }

    /** {@code /api/v1/catalogs/<encoded id>} — every id reaching a path goes through {@code segment}. */
    private static String catalogPath(String catalogId) {
        return CATALOGS_PATH + "/" + CatalogApiClient.segment(catalogId);
    }
}
