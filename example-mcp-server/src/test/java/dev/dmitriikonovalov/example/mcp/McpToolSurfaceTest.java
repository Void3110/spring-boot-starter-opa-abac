package dev.dmitriikonovalov.example.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.mcp.tool.CatalogTools;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * U1 against the <strong>real</strong> application context: every tool this server actually advertises
 * is fully classified.
 *
 * <p>That the context starts at all is itself part of the proof — the starter's startup verifier checks the
 * registry against the specifications the gate wrapped, both directions, so a surface/registry mismatch
 * would fail this test before a single assertion executed. (The contract-only classifier SPI this class
 * used to pin is not shipped — ADR 0036 §11.)
 */
@SpringBootTest
class McpToolSurfaceTest {

    @Autowired
    ToolRegistry registry;

    /** By name — the context holds more than one specification list (see ToolGateInstallationTest). */
    @Autowired
    Map<String, List<SyncToolSpecification>> specificationLists;

    @Test // U9 — every tool advertises itself as the read it is, so an MCP host does not label it destructive
    void everyAdvertisedToolCarriesReadOnlyHints() {
        List<McpSchema.Tool> tools = specificationLists.values().stream()
                .flatMap(List::stream)
                .map(SyncToolSpecification::tool)
                .toList();
        assertThat(tools).as("the scanner must have produced the tool list").isNotEmpty();

        // @McpTool's defaults are destructiveHint=true and openWorldHint=true, and Claude Code shows them
        // as "destructive · open-world" (QA E13). The hints are advisory — the tool-gate decides on each
        // tool's declared category and risk tier — but a read must not advertise itself as a write.
        for (McpSchema.Tool tool : tools) {
            assertThat(tool.annotations()).as("annotations of %s", tool.name()).isNotNull();
            assertThat(tool.annotations().readOnlyHint()).as("readOnlyHint of %s", tool.name()).isTrue();
            assertThat(tool.annotations().destructiveHint()).as("destructiveHint of %s", tool.name()).isFalse();
            assertThat(tool.annotations().idempotentHint()).as("idempotentHint of %s", tool.name()).isTrue();
            assertThat(tool.annotations().openWorldHint()).as("openWorldHint of %s", tool.name()).isFalse();
        }
    }

    @Test // U1
    void everyAdvertisedToolDeclaresAnActionCategoryAndRiskTag() {
        Set<String> advertised = Arrays.stream(CatalogTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(Objects::nonNull)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertThat(advertised).containsExactlyInAnyOrder(
                CatalogTools.LIST_CATALOGS,
                CatalogTools.GET_CATALOG,
                CatalogTools.LIST_CATEGORIES,
                CatalogTools.GET_PRODUCT);

        for (String toolName : advertised) {
            assertThat(registry.find(toolName))
                    .as("declaration for %s", toolName)
                    .hasValueSatisfying(descriptor -> {
                        assertThat(descriptor.action()).isNotBlank();
                        assertThat(descriptor.category()).isNotBlank();
                        assertThat(descriptor.targetType()).isNotBlank();
                        assertThat(descriptor.riskTags()).isNotEmpty();
                    });
        }
    }

    @Test // U1 — the declared attributes are exactly what the registry serves, with no defaulting
    void servesTheDeclaredAttributesVerbatim() {
        ToolDescriptor product = registry.find(CatalogTools.GET_PRODUCT).orElseThrow();

        assertThat(product.action()).isEqualTo("view");
        assertThat(product.category()).isEqualTo("READ");
        assertThat(product.targetType()).isEqualTo("product");
        assertThat(product.riskTags()).containsExactly("medium");

        // The target type is what lets the tool-gate derive the ceiling through the SHIPPED role
        // model, and it is the type the catalog service will gate again downstream.
        assertThat(registry.find(CatalogTools.LIST_CATALOGS).orElseThrow().targetType())
                .isEqualTo("catalog");
        assertThat(registry.find(CatalogTools.LIST_CATEGORIES).orElseThrow().targetType())
                .isEqualTo("category");

        // The allow/deny contrast this slice demonstrates: the product read sits a risk tier above the
        // structural reads, so a capability capped at "low" is narrowed without gating any mutation.
        assertThat(registry.find(CatalogTools.LIST_CATALOGS).orElseThrow().riskTags())
                .containsExactly("low");
    }

    @Test // U1 — an undeclared tool name resolves to nothing, never to a permissive default
    void doesNotInventDeclarationsForUnknownTools() {
        assertThat(registry.find("delete_everything")).isEmpty();
    }
}
