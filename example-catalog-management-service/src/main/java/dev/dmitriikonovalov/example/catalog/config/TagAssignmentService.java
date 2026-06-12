package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Validates a submitted {@code tags} map against the dictionary and produces the {@link ResourceTags} to
 * persist on a resource. The dictionary is fetched from the user-service via {@link TagDefinitionClient}
 * (fail-closed: a fetch failure throws, so the write is rejected — never persisted untagged).
 *
 * <p>This is the <b>assignment</b> layer: the dictionary constrains <em>what</em> is legal; the existing
 * {@code @OpaPreAuthorize(category:assign-tags)} (the delta-dispatched second decision) governs <em>who</em> may assign. Every submitted entry must
 * resolve to a known key and pass its value type / cardinality; an unknown key or illegal value throws
 * {@link IllegalTagAssignmentException} (→ 422), naming the offending key. Nothing is silently dropped.
 *
 * <p>Stored shape matches the definition's cardinality: {@code SINGLE} → a scalar string tag,
 * {@code MULTI} → a string-array tag — exactly what {@link ResourceTags} already serializes.
 */
@Service
public class TagAssignmentService {

    private final TagDefinitionClient tagDefinitions;

    public TagAssignmentService(TagDefinitionClient tagDefinitions) {
        this.tagDefinitions = tagDefinitions;
    }

    /**
     * Validate {@code submittedTags} for a resource and return the {@link ResourceTags} to persist. An
     * empty/absent map yields {@link ResourceTags#empty()} without a definitions fetch.
     *
     * @param resourceType the resource type being tagged (e.g. {@code "category"})
     * @param resourceId   the resource id (used to resolve the governing team's keys)
     * @param submittedTags {@code key -> scalar String | List<String>} as posted by the client
     */
    public ResourceTags validateAndBuild(
            String resourceType, String resourceId, Map<String, Object> submittedTags) {
        if (submittedTags == null || submittedTags.isEmpty()) {
            return ResourceTags.empty();
        }
        Map<String, TagDefinitionView> byKey =
                tagDefinitions.fetchApplicable(resourceType, resourceId).stream()
                        .collect(Collectors.toMap(
                                TagDefinitionView::key, d -> d, (a, b) -> a));

        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : submittedTags.entrySet()) {
            String key = entry.getKey();
            TagDefinitionView def = byKey.get(key);
            if (def == null) {
                throw new IllegalTagAssignmentException(
                        "Unknown tag key '" + key + "' (no applicable definition)");
            }
            validated.put(key, validatedValue(def, entry.getValue()));
        }
        return ResourceTags.fromMap(validated);
    }

    private static Object validatedValue(TagDefinitionView def, Object raw) {
        if (def.isMulti()) {
            List<String> values = asStringList(def.key(), raw);
            if (values.isEmpty()) {
                throw new IllegalTagAssignmentException(
                        "Tag '" + def.key() + "' is multi-valued and requires a non-empty list");
            }
            values.forEach(v -> checkValue(def, v));
            return values;
        }
        if (raw instanceof List<?>) {
            throw new IllegalTagAssignmentException(
                    "Tag '" + def.key() + "' is single-valued and does not accept a list");
        }
        String value = asString(def.key(), raw);
        checkValue(def, value);
        return value;
    }

    private static void checkValue(TagDefinitionView def, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalTagAssignmentException("Tag '" + def.key() + "' has an empty value");
        }
        if (def.isEnum()) {
            if (!def.allowedValues().contains(value)) {
                throw new IllegalTagAssignmentException("Tag '" + def.key() + "' value '" + value
                        + "' is not one of " + def.allowedValues());
            }
            return;
        }
        String pattern = def.valuePattern();
        if (pattern != null && !pattern.isBlank()) {
            try {
                if (!Pattern.matches(pattern, value)) {
                    throw new IllegalTagAssignmentException("Tag '" + def.key() + "' value '" + value
                            + "' does not match the required pattern");
                }
            } catch (PatternSyntaxException ex) {
                // A malformed stored pattern must not silently pass the value — fail closed.
                throw new IllegalTagAssignmentException(
                        "Tag '" + def.key() + "' has an invalid value pattern");
            }
        }
    }

    private static String asString(String key, Object raw) {
        if (raw instanceof String s) {
            return s;
        }
        throw new IllegalTagAssignmentException("Tag '" + key + "' must be a string value");
    }

    private static List<String> asStringList(String key, Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalTagAssignmentException(
                    "Tag '" + key + "' is multi-valued and requires a list of strings");
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String s)) {
                throw new IllegalTagAssignmentException(
                        "Tag '" + key + "' values must all be strings");
            }
            values.add(s);
        }
        return values;
    }
}
