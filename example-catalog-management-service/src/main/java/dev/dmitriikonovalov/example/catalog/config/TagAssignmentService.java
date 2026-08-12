package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
     * <p><b>Address the dictionary by the governing root.</b> The user-service resolves the applicable
     * team by exact team-target match, and teams target roots (catalogs) — so callers pass the tagged
     * resource's GOVERNING ROOT here, not the resource itself (the caller-resolves-the-root rule the
     * effective-role fetch already follows). Passing a non-root resolves no team: the globals still
     * validate, but the team's custom keys silently stop applying.
     *
     * @param resourceType the governing root's type (e.g. {@code "catalog"} for category tags)
     * @param resourceId   the governing root's id (resolves the team whose custom keys apply)
     * @param submittedTags {@code key -> scalar String | List<String>} as posted by the client
     */
    public ResourceTags validateAndBuild(
            String resourceType, String resourceId, Map<String, Object> submittedTags) {
        return validateAndBuild(resourceType, resourceId, submittedTags, Map.of());
    }

    /**
     * The full public-path form: validate {@code submittedTags} <em>against the resource's current
     * tags</em>, so operator-managed keys can be protected.
     *
     * <p>Writes here are <b>full-map replace</b>, which is what makes the rejection <b>delta-based</b>
     * rather than presence-based: for every key whose definition is operator-managed, the submitted map's
     * presence-and-value must equal the current map's. An <b>assign</b> (absent → present), a
     * <b>re-value</b>, or a <b>strip</b> (present → absent) throws {@link TagOperatorManagedException};
     * an <b>echo</b> — the same value on both sides, or absent on both — passes. The echo case is not a
     * concession: rejecting it would freeze every ordinary tag edit on a resource that happens to carry
     * an operator-managed key, and a frozen edit path is what makes people look for a workaround.
     *
     * <p>Note the empty-submission fast path may only skip the dictionary fetch when there is nothing to
     * protect either: submitting {@code null}/{@code {}} over a resource that currently carries an
     * operator-managed key <b>is a strip</b>, and must be rejected rather than shortcut.
     *
     * @param currentTags the resource's persisted tags ({@code {}} for a create — nothing to protect yet,
     *     so submitting an operator-managed key on create is an assign and is rejected)
     */
    public ResourceTags validateAndBuild(
            String resourceType,
            String resourceId,
            Map<String, Object> submittedTags,
            Map<String, Object> currentTags) {
        Map<String, Object> submitted = submittedTags == null ? Map.of() : submittedTags;
        Map<String, Object> current = currentTags == null ? Map.of() : currentTags;
        if (submitted.isEmpty() && current.isEmpty()) {
            return ResourceTags.empty();
        }
        Map<String, TagDefinitionView> byKey = applicableByKey(resourceType, resourceId);
        rejectOperatorManagedDelta(byKey, submitted, current);
        return validateAgainst(byKey, submitted);
    }

    /**
     * The <b>operator's</b> form: validate values against the dictionary but run <em>no</em>
     * operator-managed delta check — this path <b>is</b> the operator, so there is nothing for it to be
     * rejected by. The bypass is by construction (a separate entry point that never calls the check)
     * rather than by a flag a caller could pass, so no public request can reach it.
     *
     * <p>Values are still fully validated: an unknown key or an illegal enum value is rejected here
     * exactly as on the public path.
     */
    public ResourceTags validateAsOperator(
            String resourceType, String resourceId, Map<String, Object> tags) {
        if (tags == null || tags.isEmpty()) {
            return ResourceTags.empty();
        }
        return validateAgainst(applicableByKey(resourceType, resourceId), tags);
    }

    private Map<String, TagDefinitionView> applicableByKey(String resourceType, String resourceId) {
        return tagDefinitions.fetchApplicable(resourceType, resourceId).stream()
                .collect(Collectors.toMap(TagDefinitionView::key, d -> d, (a, b) -> a));
    }

    private static ResourceTags validateAgainst(
            Map<String, TagDefinitionView> byKey, Map<String, Object> submittedTags) {
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

    /**
     * The delta check, over the <b>union</b> of submitted and current keys — a strip only shows up on the
     * current side, an assign only on the submitted side, so iterating either map alone would miss half
     * the ways an operator-managed key can move.
     */
    private static void rejectOperatorManagedDelta(
            Map<String, TagDefinitionView> byKey,
            Map<String, Object> submitted,
            Map<String, Object> current) {
        Set<String> touched = new LinkedHashSet<>(submitted.keySet());
        touched.addAll(current.keySet());
        for (String key : touched) {
            TagDefinitionView def = byKey.get(key);
            if (def == null || !def.isOperatorManaged()) {
                continue;
            }
            boolean wasPresent = current.containsKey(key);
            boolean isPresent = submitted.containsKey(key);
            if (wasPresent != isPresent || !Objects.equals(current.get(key), submitted.get(key))) {
                throw new TagOperatorManagedException("Tag '" + key
                        + "' is operator-managed: its value cannot be assigned, changed or removed"
                        + " through the API");
            }
        }
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
            } catch (PatternSyntaxException _) {
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
