package dev.dmitriikonovalov.opaabac.data.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;

/**
 * Serializes {@link ResourceTags} to/from the JSON text stored in the {@code tags} JSONB column.
 *
 * <p>Pairs with {@code @JdbcTypeCode(SqlTypes.JSON)} on the field: Hibernate binds the produced
 * string as real {@code jsonb}, while this converter owns the exact JSON <em>shape</em> via a single
 * shared {@link ObjectMapper}. Empty/null tags map to {@code "{}"} (never SQL {@code NULL}), so the
 * column can stay {@code NOT NULL} and policies always see an object.
 *
 * <p>This is the idiomatic Hibernate 6.4 approach (converter + JSON jdbc type); a hand-rolled
 * {@code UserType} is a contingency that isn't needed here. Keeping serialization in a plain
 * converter also makes the round-trip unit-testable without a database.
 */
@Converter
public class ResourceTagsConverter implements AttributeConverter<ResourceTags, String> {

    private static final String EMPTY_JSON = "{}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(ResourceTags attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return EMPTY_JSON;
        }
        try {
            return MAPPER.writeValueAsString(attribute.asMap());
        } catch (JsonProcessingException e) {
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize ResourceTags from JSON: " + dbData, e);
        }
    }
}
