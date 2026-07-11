package dev.dmitriikonovalov.opaabac.data.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * W3 (SB4 port) — wire-format parity of the jsonb tags column across the Jackson 2 → 3 swap.
 *
 * <p>Existing databases hold rows written by the Jackson-2 converter; the Jackson-3 converter must
 * keep round-tripping them. The literal below is hardcoded <em>exactly as Jackson 2.18 serialized
 * it</em> (compact separators, insertion order, non-ASCII unescaped, integral numbers unquoted) —
 * regenerating it with the current mapper would make the test circular. A silent regression here
 * corrupts tag-based authorization, so this is the port's headline fail-closed pin.
 */
class ResourceTagsConverterParityTest {

    private final ResourceTagsConverter converter = new ResourceTagsConverter();

    /** Exactly what the Jackson-2.18 converter wrote for the map below — do not regenerate. */
    private static final String JACKSON2_LITERAL =
            "{\"tier\":\"gold\",\"region\":[\"emea\",\"amer\"],\"владелец\":\"команда-А\","
                    + "\"count\":3,\"enabled\":true,\"nested\":{\"k\":\"v\"}}";

    @Test // W3a — a Jackson-2-written row reads back into the same values on Jackson 3
    void jackson2WrittenLiteral_readsBackEqual() {
        ResourceTags back = converter.convertToEntityAttribute(JACKSON2_LITERAL);

        assertThat(back.string("tier")).isEqualTo("gold");
        assertThat(back.list("region")).containsExactly("emea", "amer");
        assertThat(back.string("владелец")).isEqualTo("команда-А"); // non-ASCII survives unescaped
        assertThat(back.asMap().get("count")).isEqualTo(3); // integral stays integral, not 3.0
        assertThat(back.asMap().get("enabled")).isEqualTo(true);
        assertThat(back.asMap().get("nested")).isEqualTo(Map.of("k", "v"));
    }

    @Test // W3b — the Jackson-3 rewrite of that row round-trips to the same values (byte order is
    // irrelevant on the wire: Postgres jsonb does not preserve key order — value parity is the pin)
    void jackson3Rewrite_roundTripsEqual() {
        ResourceTags original = converter.convertToEntityAttribute(JACKSON2_LITERAL);

        String rewritten = converter.convertToDatabaseColumn(original);
        ResourceTags back = converter.convertToEntityAttribute(rewritten);

        assertThat(back).isEqualTo(original);
    }

    @Test // W3c — the Jackson-3 converter still writes the exact compact form for a fresh row
    void jackson3Write_isCompactInsertionOrder() {
        ResourceTags tags = ResourceTags.empty()
                .with("tier", "gold")
                .with("region", List.of("emea", "amer"));

        assertThat(converter.convertToDatabaseColumn(tags))
                .isEqualTo("{\"tier\":\"gold\",\"region\":[\"emea\",\"amer\"]}");
    }

    @Test // W3d — empty/null still map to "{}", never SQL NULL (the NOT NULL column contract)
    void emptyStaysEmptyObjectNeverSqlNull() {
        assertThat(converter.convertToDatabaseColumn(ResourceTags.empty())).isEqualTo("{}");
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("{}");
        assertThat(converter.convertToEntityAttribute("{}").isEmpty()).isTrue();
    }
}
