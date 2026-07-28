package dev.dmitriikonovalov.example.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** U1: lookups resolve declared tools and return <em>empty</em> — never a permissive default — otherwise. */
class ToolRegistryTest {

    private static final ToolDescriptor LIST_CATALOGS =
            new ToolDescriptor("list_catalogs", "list", "READ", "catalog", Set.of("low"));
    private static final ToolDescriptor GET_PRODUCT =
            new ToolDescriptor("get_product", "view", "READ", "product", Set.of("medium"));

    @Test // U1
    void resolvesADeclaredTool() {
        ToolRegistry registry = new ToolRegistry(List.of(LIST_CATALOGS, GET_PRODUCT));

        assertThat(registry.find("get_product")).contains(GET_PRODUCT);
        assertThat(registry.all()).containsExactly(LIST_CATALOGS, GET_PRODUCT);
        assertThat(registry.names()).containsExactly("list_catalogs", "get_product");
    }

    @Test // the roster pre-flight pairs these two positionally, so the order must be stable
    void servesDeclarationOrderAndKeepsNamesAlignedWithDescriptors() {
        ToolRegistry registry = new ToolRegistry(List.of(GET_PRODUCT, LIST_CATALOGS));

        assertThat(registry.all()).containsExactly(GET_PRODUCT, LIST_CATALOGS);
        assertThat(registry.names()).containsExactly("get_product", "list_catalogs");

        // Repeated reads agree with each other and with all() index-for-index.
        for (int i = 0; i < registry.all().size(); i++) {
            assertThat(registry.names().get(i)).isEqualTo(registry.all().get(i).name());
        }
        assertThat(registry.all()).isEqualTo(registry.all());
    }

    @Test // U1 — the fail-closed lookup: an undeclared tool has no attributes to authorize against
    void returnsEmptyForAnUndeclaredTool() {
        ToolRegistry registry = new ToolRegistry(List.of(LIST_CATALOGS));

        assertThat(registry.find("delete_everything")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test // U1 — an ambiguous declaration would make the gate's answer depend on map ordering
    void rejectsDuplicateToolNames() {
        ToolDescriptor duplicate = new ToolDescriptor("list_catalogs", "view", "READ", "catalog", Set.of("high"));

        assertThatThrownBy(() -> new ToolRegistry(List.of(LIST_CATALOGS, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list_catalogs");
    }
}
