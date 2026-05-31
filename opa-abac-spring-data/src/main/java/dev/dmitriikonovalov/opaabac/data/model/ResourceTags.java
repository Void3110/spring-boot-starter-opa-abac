package dev.dmitriikonovalov.opaabac.data.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable set of ABAC tags attached to a secured resource, stored as a JSONB column.
 *
 * <p>Tags are the resource-side attributes an OPA policy reads. They deliberately
 * <strong>preserve JSON types</strong> rather than flattening everything to strings:
 * <ul>
 *   <li><b>string tags</b> — {@code "tier": "gold"}</li>
 *   <li><b>array tags</b> — {@code "members": ["u1", "u2"]} (membership; the key enabler for
 *       later data-filtering queries such as {@code jsonb_exists(tags -> 'members', :user)})</li>
 *   <li><b>map-of-lists</b> — structured grouping</li>
 * </ul>
 *
 * <p>The class is immutable: every mutating helper ({@link #with(String, Object)}) returns a new
 * instance, and copies are taken defensively on the way in and out. Jackson serializes it to its
 * underlying map via {@link JsonValue} and rebuilds it via {@link #fromMap(Map)}.
 */
public final class ResourceTags {

    private static final ResourceTags EMPTY = new ResourceTags(Map.of());

    private final Map<String, Object> values;

    private ResourceTags(Map<String, Object> values) {
        // Deep-ish defensive copy: copy the top-level map and any collection values so callers
        // can't mutate our internals through a reference they still hold.
        this.values = deepCopy(values);
    }

    /** The empty tag set (serializes to {@code {}}). */
    public static ResourceTags empty() {
        return EMPTY;
    }

    /** Build from a raw map (e.g. Jackson deserialization or application code). Null → empty. */
    @JsonCreator
    public static ResourceTags fromMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        return new ResourceTags(values);
    }

    /** The underlying map (an unmodifiable copy). Also the Jackson serialization form. */
    @JsonValue
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(deepCopy(values));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** The string value at {@code key}, or {@code null} if absent or not a string. */
    public String string(String key) {
        Object value = values.get(key);
        return value instanceof String s ? s : null;
    }

    /** The list value at {@code key} as an unmodifiable copy, or an empty list if absent/not a list. */
    public List<Object> list(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
        return List.of();
    }

    /**
     * Whether {@code value} is present at {@code key}: true if the tag is a list containing it, or a
     * scalar equal to it. False when the key is absent.
     */
    public boolean contains(String key, Object value) {
        Object current = values.get(key);
        if (current instanceof List<?> list) {
            return list.contains(value);
        }
        return Objects.equals(current, value);
    }

    /** A new {@code ResourceTags} with {@code key} set to {@code value} (this instance is unchanged). */
    public ResourceTags with(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put(key, value);
        return new ResourceTags(next);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ResourceTags other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "ResourceTags" + values;
    }

    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopy((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(copyValue(element));
            }
            return copy;
        }
        return value;
    }
}
