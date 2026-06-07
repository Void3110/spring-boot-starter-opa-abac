package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ParentRef} (QA case U1) — the neutral one-hop parent reference. */
class ParentRefTest {

    @Test // U1 — both components are required
    void rejectsNullType() {
        assertThatThrownBy(() -> new ParentRef(null, "1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
    }

    @Test // U1 — both components are required
    void rejectsNullId() {
        assertThatThrownBy(() -> new ParentRef("catalog", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    void holdsTypeAndId() {
        ParentRef ref = new ParentRef("catalog", "1");
        assertThat(ref.type()).isEqualTo("catalog");
        assertThat(ref.id()).isEqualTo("1");
    }
}
