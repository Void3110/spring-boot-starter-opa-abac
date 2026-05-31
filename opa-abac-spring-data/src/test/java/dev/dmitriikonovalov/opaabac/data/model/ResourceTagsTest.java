package dev.dmitriikonovalov.opaabac.data.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceTagsTest {

    @Test
    void withReturnsNewInstanceAndLeavesOriginalUnchanged() { // U1
        ResourceTags original = ResourceTags.empty();

        ResourceTags updated = original.with("type", "product");

        assertThat(updated.asMap()).containsExactly(Map.entry("type", "product"));
        assertThat(original.isEmpty()).isTrue();
        assertThat(original).isNotSameAs(updated);
    }

    @Test
    void containsHandlesArrayAndScalarTags() { // U5
        ResourceTags tags = ResourceTags.empty()
                .with("members", List.of("u1", "u2"))
                .with("tier", "gold");

        assertThat(tags.contains("members", "u1")).isTrue();
        assertThat(tags.contains("members", "u3")).isFalse();
        assertThat(tags.contains("tier", "gold")).isTrue();
        assertThat(tags.contains("tier", "silver")).isFalse();
        assertThat(tags.contains("absent", "x")).isFalse();
    }

    @Test
    void stringAndListAccessorsAreTypeSafe() {
        ResourceTags tags = ResourceTags.empty()
                .with("tier", "gold")
                .with("members", List.of("u1", "u2"));

        assertThat(tags.string("tier")).isEqualTo("gold");
        assertThat(tags.string("members")).isNull(); // not a string
        assertThat(tags.list("members")).containsExactly("u1", "u2");
        assertThat(tags.list("tier")).isEmpty(); // not a list
    }

    @Test
    void asMapIsAnImmutableDefensiveCopy() {
        ResourceTags tags = ResourceTags.empty().with("k", "v");

        Map<String, Object> map = tags.asMap();

        // Mutating the returned map must not affect the tags.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> map.put("k2", "v2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(tags.asMap()).containsOnlyKeys("k");
    }

    @Test
    void mutatingAnInputCollectionDoesNotLeakIntoTags() {
        java.util.List<String> members = new java.util.ArrayList<>(List.of("u1"));
        ResourceTags tags = ResourceTags.empty().with("members", members);

        members.add("u2"); // mutate the caller's list after constructing

        assertThat(tags.list("members")).containsExactly("u1");
    }

    @Test
    void equalityIsValueBased() {
        ResourceTags a = ResourceTags.empty().with("tier", "gold");
        ResourceTags b = ResourceTags.fromMap(Map.of("tier", "gold"));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
