package dev.dmitriikonovalov.example.catalog.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The catalog's read model of a user-service tag definition (the subset the catalog needs to validate an
 * assigned tag). Deserialized from {@code GET /internal/tag-definitions}; unknown fields are ignored so
 * the user-service can add fields without breaking the catalog.
 *
 * @param key             the tag key
 * @param valueType       {@code "STRING"} | {@code "ENUM"}
 * @param cardinality     {@code "SINGLE"} | {@code "MULTI"}
 * @param allowedValues   the closed set for ENUM; empty for STRING
 * @param valuePattern    an optional regex for STRING; may be {@code null}
 * @param operatorManaged whether values under this key are writable only by the operator — declared as
 *     the {@code Boolean} <b>wrapper</b> and normalized below, deliberately not a primitive: Jackson
 *     <em>throws</em> on a missing primitive record component, and a user-service that predates the flag
 *     simply omits the field. The wrapper + normalize makes "absent" read as {@code false}, so
 *     back-compat is a property of the shape rather than of a version check.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TagDefinitionView(
        String key,
        String valueType,
        String cardinality,
        List<String> allowedValues,
        String valuePattern,
        Boolean operatorManaged) {

    public TagDefinitionView {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        operatorManaged = operatorManaged != null && operatorManaged;
    }

    /** The pre-flag arity: an ordinary, non-operator-managed key. */
    public TagDefinitionView(
            String key,
            String valueType,
            String cardinality,
            List<String> allowedValues,
            String valuePattern) {
        this(key, valueType, cardinality, allowedValues, valuePattern, false);
    }

    public boolean isMulti() {
        return "MULTI".equals(cardinality);
    }

    public boolean isEnum() {
        return "ENUM".equals(valueType);
    }

    /** Never null after normalization — safe to read as a primitive. */
    public boolean isOperatorManaged() {
        return Boolean.TRUE.equals(operatorManaged);
    }
}
