package dev.dmitriikonovalov.example.catalog.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The catalog's read model of a user-service tag definition (the subset the catalog needs to validate an
 * assigned tag). Deserialized from {@code GET /internal/tag-definitions}; unknown fields are ignored so
 * the user-service can add fields without breaking the catalog.
 *
 * @param key           the tag key
 * @param valueType     {@code "STRING"} | {@code "ENUM"}
 * @param cardinality   {@code "SINGLE"} | {@code "MULTI"}
 * @param allowedValues the closed set for ENUM; empty for STRING
 * @param valuePattern  an optional regex for STRING; may be {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TagDefinitionView(
        String key,
        String valueType,
        String cardinality,
        List<String> allowedValues,
        String valuePattern) {

    public TagDefinitionView {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public boolean isMulti() {
        return "MULTI".equals(cardinality);
    }

    public boolean isEnum() {
        return "ENUM".equals(valueType);
    }
}
