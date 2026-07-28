package dev.dmitriikonovalov.example.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** U1: lookups resolve declared tools and return <em>empty</em> — never a permissive default — otherwise. */
class ToolRegistryTest {

    private static final ToolDescriptor LIST_CATALOGS =
            new ToolDescriptor("list_catalogs", "list", "READ", Set.of("low"));
    private static final ToolDescriptor GET_PRODUCT =
            new ToolDescriptor("get_product", "view", "READ", Set.of("medium"));

    @Test // U1
    void resolvesADeclaredTool() {
        ToolRegistry registry = new ToolRegistry(List.of(LIST_CATALOGS, GET_PRODUCT));

        assertThat(registry.find("get_product")).contains(GET_PRODUCT);
        assertThat(registry.all()).containsExactly(LIST_CATALOGS, GET_PRODUCT);
        assertThat(registry.names()).containsExactlyInAnyOrder("list_catalogs", "get_product");
    }

    @Test // U1 — the fail-closed lookup: an undeclared tool has no attributes to authorize against
    void returnsEmptyForAnUndeclaredTool() {
        ToolRegistry registry = new ToolRegistry(List.of(LIST_CATALOGS));

        assertThat(registry.find("delete_everything")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test // U1 — an ambiguous declaration would make the gate's answer depend on map ordering
    void rejectsDuplicateToolNames() {
        ToolDescriptor duplicate = new ToolDescriptor("list_catalogs", "view", "READ", Set.of("high"));

        assertThatThrownBy(() -> new ToolRegistry(List.of(LIST_CATALOGS, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list_catalogs");
    }
}
