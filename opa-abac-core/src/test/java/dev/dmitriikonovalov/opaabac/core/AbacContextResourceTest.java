package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the additive ancestor chain on {@link AbacContext.Resource} and the
 * {@link AbacDataObject#abacParent()} default (QA cases U2–U4). The chain serializes as
 * {@code input.resource.ancestors}, root-first and leaf-excluded, and is <b>omitted when empty</b> so a
 * non-hierarchical resource serializes byte-for-byte as before.
 */
class AbacContextResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test // U2 — ancestors serialize as input.resource.ancestors, ordered root-first, leaf-excluded
    void ancestorsSerializeAsOrderedRootFirstList() throws Exception {
        AbacContext.Resource resource = new AbacContext.Resource(
                "product",
                "42",
                Map.of(),
                List.of(new ParentRef("catalog", "1"), new ParentRef("category", "7")));

        JsonNode node = MAPPER.valueToTree(resource);
        JsonNode ancestors = node.get("ancestors");
        assertThat(ancestors).isNotNull();
        assertThat(ancestors.isArray()).isTrue();
        assertThat(ancestors).hasSize(2);
        // root-first
        assertThat(ancestors.get(0).get("type").asText()).isEqualTo("catalog");
        assertThat(ancestors.get(0).get("id").asText()).isEqualTo("1");
        assertThat(ancestors.get(1).get("type").asText()).isEqualTo("category");
        assertThat(ancestors.get(1).get("id").asText()).isEqualTo("7");
        // leaf excluded — the leaf is the resource's own type/id, never repeated in the chain
        for (JsonNode anc : ancestors) {
            assertThat(anc.get("id").asText()).isNotEqualTo("42");
        }
    }

    @Test // U3 — no ancestors → byte-for-byte as before (no "ancestors" key)
    void noAncestorsSerializesByteForByteAsBefore() throws Exception {
        AbacContext.Resource legacy = new AbacContext.Resource("product", "42", Map.of("region", "emea"));
        AbacContext.Resource explicitEmpty =
                new AbacContext.Resource("product", "42", Map.of("region", "emea"), List.of());

        String legacyJson = MAPPER.writeValueAsString(legacy);
        String explicitEmptyJson = MAPPER.writeValueAsString(explicitEmpty);

        assertThat(legacyJson).doesNotContain("ancestors");
        assertThat(explicitEmptyJson).doesNotContain("ancestors");
        // identical wire shape regardless of which constructor produced the empty chain
        assertThat(explicitEmptyJson).isEqualTo(legacyJson);
        // the exact prior field set — only type/id/attributes
        assertThat(MAPPER.readTree(legacyJson).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("type", "id", "attributes");
    }

    @Test // U4 — abacParent() defaults to empty on a plain AbacDataObject
    void abacParentDefaultsToEmpty() {
        AbacDataObject plain = new AbacDataObject() {
            @Override
            public String abacResourceType() {
                return "catalog";
            }

            @Override
            public String abacResourceId() {
                return "1";
            }
        };
        assertThat(plain.abacParent()).isEmpty();
    }

    @Test // U4 — a hierarchical object can declare its one hop
    void abacParentCanDeclareOneHop() {
        AbacDataObject child = new AbacDataObject() {
            @Override
            public String abacResourceType() {
                return "product";
            }

            @Override
            public String abacResourceId() {
                return "42";
            }

            @Override
            public Optional<ParentRef> abacParent() {
                return Optional.of(new ParentRef("category", "7"));
            }
        };
        assertThat(child.abacParent()).contains(new ParentRef("category", "7"));
    }

    @Test // U4 — the back-compat 3-arg ctor yields an empty ancestor chain
    void backCompatConstructorYieldsEmptyAncestors() {
        AbacContext.Resource resource = new AbacContext.Resource("product", "42", Map.of());
        assertThat(resource.ancestors()).isEmpty();
    }

    @Test // ancestors are defensively copied and the returned list is immutable
    void ancestorsDefensiveCopyAndImmutable() {
        List<ParentRef> chain = new ArrayList<>(List.of(new ParentRef("catalog", "1")));
        AbacContext.Resource resource = new AbacContext.Resource("category", "7", Map.of(), chain);

        chain.add(new ParentRef("injected", "x"));

        assertThat(resource.ancestors()).hasSize(1);
        assertThatThrownBy(() -> resource.ancestors().add(new ParentRef("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test // a null ancestor list normalizes to empty (defensive)
    void nullAncestorsNormalizeToEmpty() {
        AbacContext.Resource resource = new AbacContext.Resource("product", "42", Map.of(), null);
        assertThat(resource.ancestors()).isEmpty();
    }
}
