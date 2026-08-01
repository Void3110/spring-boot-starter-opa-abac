package dev.dmitriikonovalov.example.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * U1/U2 (unit half): a descriptor carries exactly what was declared, and an <em>unclassifiable</em>
 * declaration is rejected at construction rather than defaulted to something permissive.
 */
class ToolDescriptorTest {

    @Test // U1
    void carriesTheDeclaredTripleVerbatim() {
        ToolDescriptor descriptor =
                new ToolDescriptor("get_product", "view", "READ", "product", Set.of("medium", "pii"));

        assertThat(descriptor.name()).isEqualTo("get_product");
        assertThat(descriptor.action()).isEqualTo("view");
        assertThat(descriptor.category()).isEqualTo("READ");
        assertThat(descriptor.riskTags()).containsExactlyInAnyOrder("medium", "pii");
    }

    @Test // U2 — the risk tags cannot be mutated after declaration
    void riskTagsAreDefensivelyCopiedAndImmutable() {
        Set<String> mutable = new HashSet<>(Set.of("low"));
        ToolDescriptor descriptor = new ToolDescriptor("t", "view", "READ", "product", mutable);

        mutable.add("high");

        assertThat(descriptor.riskTags()).containsExactly("low");
        assertThatThrownBy(() -> descriptor.riskTags().add("high"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test // U2
    void rejectsABlankCategory() {
        assertThatThrownBy(() -> new ToolDescriptor("get_product", "view", "  ", "product", Set.of("low")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("get_product")
                .hasMessageContaining("category");
    }

    @Test // U2
    void rejectsABlankAction() {
        assertThatThrownBy(() -> new ToolDescriptor("get_product", null, "READ", "product", Set.of("low")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test // U2
    void rejectsABlankName() {
        assertThatThrownBy(() -> new ToolDescriptor("", "view", "READ", "product", Set.of("low")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test // U2 — no risk tags at all is unclassifiable, not "no risk"
    void rejectsAnEmptyRiskTagSet() {
        assertThatThrownBy(() -> new ToolDescriptor("get_product", "view", "READ", "product", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risk tags");
    }

    @Test // U2
    void rejectsABlankRiskTag() {
        assertThatThrownBy(() -> new ToolDescriptor("get_product", "view", "READ", "product", Set.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risk tag");
    }
}
