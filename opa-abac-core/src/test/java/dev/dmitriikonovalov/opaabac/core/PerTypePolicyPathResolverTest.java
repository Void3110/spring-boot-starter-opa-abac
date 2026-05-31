package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PerTypePolicyPathResolver} path joining (supports U8). */
class PerTypePolicyPathResolverTest {

    private AbacContext ctxForType(String type) {
        return new AbacContext(
                new AbacContext.Subject("u", null, null),
                "read",
                new AbacContext.Resource(type, null, null),
                Map.of());
    }

    @Test
    void prefixAndType() {
        assertThat(new PerTypePolicyPathResolver("catalog").resolve(ctxForType("product")))
                .isEqualTo("catalog/product");
    }

    @Test
    void blankPrefix_returnsTypeOnly() {
        assertThat(new PerTypePolicyPathResolver("").resolve(ctxForType("product"))).isEqualTo("product");
        assertThat(new PerTypePolicyPathResolver(null).resolve(ctxForType("category"))).isEqualTo("category");
    }

    @Test
    void trimsSurroundingSlashes() {
        assertThat(new PerTypePolicyPathResolver("/catalog/").resolve(ctxForType("product")))
                .isEqualTo("catalog/product");
    }

    @Test
    void blankType_returnsPrefixOnly() {
        assertThat(new PerTypePolicyPathResolver("catalog").resolve(ctxForType(""))).isEqualTo("catalog");
    }
}
