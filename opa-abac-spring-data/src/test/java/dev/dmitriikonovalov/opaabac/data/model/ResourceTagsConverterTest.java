package dev.dmitriikonovalov.opaabac.data.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceTagsConverterTest {

    private final ResourceTagsConverter converter = new ResourceTagsConverter();

    @Test
    void arrayTagSurvivesRoundTripAsList() { // U2
        ResourceTags tags = ResourceTags.empty().with("members", List.of("u1", "u2"));

        String json = converter.convertToDatabaseColumn(tags);
        ResourceTags back = converter.convertToEntityAttribute(json);

        assertThat(json).isEqualTo("{\"members\":[\"u1\",\"u2\"]}");
        assertThat(back.list("members")).containsExactly("u1", "u2"); // a List, not a stringified value
        assertThat(back).isEqualTo(tags);
    }

    @Test
    void mapOfListsRoundTripPreservesStructure() { // U3
        ResourceTags tags = ResourceTags.empty()
                .with("acl", Map.of("viewers", List.of("a", "b"), "editors", List.of("c")));

        ResourceTags back = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(tags));

        assertThat(back).isEqualTo(tags);
        @SuppressWarnings("unchecked")
        Map<String, Object> acl = (Map<String, Object>) back.asMap().get("acl");
        assertThat(acl.get("viewers")).isEqualTo(List.of("a", "b"));
        assertThat(acl.get("editors")).isEqualTo(List.of("c"));
    }

    @Test
    void emptyTagsSerializeToEmptyObject() { // U4
        assertThat(converter.convertToDatabaseColumn(ResourceTags.empty())).isEqualTo("{}");
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("{}");
    }

    @Test
    void nullOrBlankColumnDeserializesToEmptyTags() { // U4
        assertThat(converter.convertToEntityAttribute(null).isEmpty()).isTrue();
        assertThat(converter.convertToEntityAttribute("").isEmpty()).isTrue();
        assertThat(converter.convertToEntityAttribute("{}").isEmpty()).isTrue();
    }

    @Test
    void stringTagRoundTrips() {
        ResourceTags tags = ResourceTags.empty().with("tier", "gold");

        ResourceTags back = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(tags));

        assertThat(back.string("tier")).isEqualTo("gold");
    }
}
