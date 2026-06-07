package dev.dmitriikonovalov.opaabac.data.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit tests for {@link AbstractHierarchicalEntity}'s in-memory behavior: the self-label encoding and
 * the path accessor (the persistence behavior is covered by {@code AbstractHierarchicalEntityIT}; the
 * I9 "non-hierarchical entities are unaffected" guard is the unchanged {@code AbstractSecuredEntityTest}).
 */
class AbstractHierarchicalEntityTest {

    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CATALOG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final class TestCategory extends AbstractHierarchicalEntity {
        TestCategory(UUID id) {
            super(id);
        }

        @Override
        public String abacResourceType() {
            return "category";
        }

        @Override
        public Optional<ParentRef> abacParent() {
            return Optional.of(new ParentRef("catalog", CATALOG_ID.toString()));
        }
    }

    @Test // self-label encodes <type>_<dash-free-hex-uuid>, matching the resolver's decoder
    void selfLabelEncodesTypeAndUuid() {
        TestCategory category = new TestCategory(CATEGORY_ID);
        assertThat(category.selfLabel()).isEqualTo("category_" + CATEGORY_ID.toString().replace("-", ""));
    }

    @Test // the path accessor is a plain getter/setter (the maintainer drives it)
    void pathAccessorRoundTrips() {
        TestCategory category = new TestCategory(CATEGORY_ID);
        assertThat(category.getPath()).isNull();
        category.setPath("catalog_x.category_y");
        assertThat(category.getPath()).isEqualTo("catalog_x.category_y");
    }

    @Test // abacParent declares the one hop (used by the maintainer + resolver)
    void abacParentDeclaresOneHop() {
        TestCategory category = new TestCategory(CATEGORY_ID);
        assertThat(category.abacParent()).contains(new ParentRef("catalog", CATALOG_ID.toString()));
    }

    @Test // it is still a secured entity (tags + AbacDataObject inherited)
    void remainsASecuredEntity() {
        TestCategory category = new TestCategory(CATEGORY_ID);
        assertThat(category).isInstanceOf(AbstractSecuredEntity.class);
        assertThat(category.abacResourceId()).isEqualTo(CATEGORY_ID.toString());
        assertThat(category.abacAttributes()).isEmpty(); // no tags set
    }
}
