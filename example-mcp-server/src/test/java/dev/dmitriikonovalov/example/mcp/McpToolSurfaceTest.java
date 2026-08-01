package dev.dmitriikonovalov.example.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.mcp.tool.CatalogTools;
import dev.dmitriikonovalov.example.mcp.tool.ToolCallClassifier;
import dev.dmitriikonovalov.example.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.example.mcp.tool.ToolRegistry;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * U1 + U3 against the <strong>real</strong> application context: every tool this server actually advertises
 * is fully classified, and the classifier SPI ships without an implementation on purpose.
 *
 * <p>That the context starts at all is itself part of U2 — {@code ToolRegistryValidator} runs during
 * startup, so a surface/registry mismatch would fail this test before a single assertion executed.
 */
@SpringBootTest
class McpToolSurfaceTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ToolRegistry registry;

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

    @Test // U3 — the seam ships contract-only; a stub implementation would be untestable churn
    void shipsTheClassifierSeamWithNoImplementation() {
        assertThat(ToolCallClassifier.class.isInterface()).isTrue();
        assertThat(context.getBeanNamesForType(ToolCallClassifier.class)).isEmpty();

        Method classify = ToolCallClassifier.class.getDeclaredMethods()[0];
        assertThat(classify.getName()).isEqualTo("classify");
    }
}
