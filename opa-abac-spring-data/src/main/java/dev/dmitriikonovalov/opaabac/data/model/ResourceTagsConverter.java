package dev.dmitriikonovalov.opaabac.data.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes {@link ResourceTags} to/from the JSON text stored in the {@code tags} JSONB column.
 *
 * <p>Pairs with {@code @JdbcTypeCode(SqlTypes.JSON)} on the field: Hibernate binds the produced
 * string as real {@code jsonb}, while this converter owns the exact JSON <em>shape</em> via a single
 * shared {@link ObjectMapper}. Empty/null tags map to {@code "{}"} (never SQL {@code NULL}), so the
 * column can stay {@code NOT NULL} and policies always see an object.
 *
 * <p>This is the idiomatic Hibernate converter + JSON jdbc-type approach; a hand-rolled
 * {@code UserType} is a contingency that isn't needed here. Keeping serialization in a plain
 * converter also makes the round-trip unit-testable without a database.
 *
 * <p><b>Wire parity (SB4 port, W3).</b> The column holds rows written by the Jackson-2 converter;
 * the Jackson-3 mapper must keep round-tripping them byte-compatibly. A bare {@code JsonMapper} does
 * — pinned by {@code ResourceTagsConverterParityTest}, which reads back a hardcoded
 * Jackson-2-written literal. Jackson 3 throws unchecked {@link JacksonException} (was the checked
 * {@code JsonProcessingException}); the explicit catch keeps the converter's failure contract
 * ({@code IllegalStateException}) identical.
 */
@Converter
public class ResourceTagsConverter implements AttributeConverter<ResourceTags, String> {

    private static final String EMPTY_JSON = "{}";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(ResourceTags attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return EMPTY_JSON;
        }
        try {
            return MAPPER.writeValueAsString(attribute.asMap());
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize ResourceTags to JSON", e);
        }
    }

    @Override
    public ResourceTags convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return ResourceTags.empty();
        }
        try {
            Map<String, Object> map = MAPPER.readValue(dbData, MAP_TYPE);
            return ResourceTags.fromMap(map);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize ResourceTags from JSON: " + dbData, e);
        }
    }
}
