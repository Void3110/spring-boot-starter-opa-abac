package dev.dmitriikonovalov.opaabac.data.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AbstractSecuredEntityTest {

    /** A minimal concrete secured entity, exercising the base without a JPA runtime. */
    private static class SampleEntity extends AbstractSecuredEntity {
        SampleEntity(UUID id) {
            super(id);
        }

        @Override
        public String abacResourceType() {
            return "sample";
        }
    }

    @Test
    void abacResourceTypeAndIdReflectTheEntity() { // U7
        UUID id = UUID.randomUUID();
        SampleEntity entity = new SampleEntity(id);

        assertThat(entity.abacResourceType()).isEqualTo("sample");
        assertThat(entity.abacResourceId()).isEqualTo(id.toString());
    }

    @Test
    void abacAttributesEqualTheTagsMap() { // U6
        SampleEntity entity = new SampleEntity(UUID.randomUUID());
        entity.setTags(ResourceTags.empty().with("members", List.of("u1", "u2")));

        assertThat(entity.abacAttributes())
                .isEqualTo(Map.of("members", List.of("u1", "u2")));
    }

    @Test
    void tagsDefaultToEmptyAndNullSetterIsNormalized() {
        SampleEntity entity = new SampleEntity(UUID.randomUUID());
        assertThat(entity.getTags().isEmpty()).isTrue();

        entity.setTags(null);
        assertThat(entity.getTags()).isNotNull();
        assertThat(entity.getTags().isEmpty()).isTrue();
    }

    @Test
    void equalsAndHashCodeAreIdOnly() { // U9
        UUID id = UUID.randomUUID();
        SampleEntity a = new SampleEntity(id);
        SampleEntity b = new SampleEntity(id);
        SampleEntity other = new SampleEntity(UUID.randomUUID());

        // Same id, different tag state -> still equal (id-only, ignores mutable fields).
        b.setTags(ResourceTags.empty().with("tier", "gold"));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(other);
    }

    @Test
    void unsavedEntitiesAreEqualOnlyByReference() {
        SampleEntity a = new SampleEntity(null);
        SampleEntity b = new SampleEntity(null);

        assertThat(a).isEqualTo(a);
        assertThat(a).isNotEqualTo(b); // null id -> not equal unless same instance
    }
}
