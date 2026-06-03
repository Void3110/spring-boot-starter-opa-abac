package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.TagCardinality;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.TagValueType;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * Validates a submitted tag value against its {@link TagDefinition} — the single rule used both when
 * <em>defining</em> a key (sanity-checking the definition itself, ticket 2) and when <em>assigning</em>
 * values to a resource (ticket 3). Keeping one validator means the dictionary's legality rules can never
 * drift between the two call sites.
 *
 * <p>A submitted value is modeled as a {@link SubmittedValue}: either a scalar string ({@code single}) or
 * a list of strings ({@code multi}). The validator checks three things against the definition:
 *
 * <ol>
 *   <li><b>cardinality</b> — a {@code SINGLE} key rejects a list; a {@code MULTI} key rejects a scalar;</li>
 *   <li><b>value type</b> — {@code ENUM} requires every element to be in {@code allowedValues};
 *       {@code STRING} requires every element to match {@code valuePattern} (if set);</li>
 *   <li>(empty values are rejected — a tag with no value is meaningless).</li>
 * </ol>
 *
 * <p>Fail-closed: any rule miss returns an {@link Result#invalid(String) invalid} result with a message;
 * callers turn that into a 422 and never persist the value.
 */
@Component
public class TagValueValidator {

    /** A submitted tag value: exactly one of {@code single}/{@code multi} is set. */
    public record SubmittedValue(String single, List<String> multi) {

        public static SubmittedValue ofSingle(String value) {
            return new SubmittedValue(value, null);
        }

        public static SubmittedValue ofMulti(List<String> values) {
            return new SubmittedValue(null, values == null ? List.of() : List.copyOf(values));
        }

        boolean isMulti() {
            return multi != null;
        }

        List<String> values() {
            return isMulti() ? multi : List.of(single);
        }
    }

    /** The outcome of a validation: valid, or invalid with a human-readable reason. */
    public record Result(boolean ok, String message) {

        public static Result valid() {
            return new Result(true, null);
        }

        public static Result invalid(String message) {
            return new Result(false, message);
        }
    }

    /** Validate {@code submitted} against {@code definition}. */
    public Result validate(TagDefinition definition, SubmittedValue submitted) {
        Result cardinality = checkCardinality(definition, submitted);
        if (!cardinality.ok()) {
            return cardinality;
        }
        List<String> values = submitted.values();
        if (values.isEmpty() || values.stream().anyMatch(v -> v == null || v.isBlank())) {
            return Result.invalid("Tag '" + definition.getKey() + "' has an empty value");
        }
        for (String value : values) {
            Result element = checkValue(definition, value);
            if (!element.ok()) {
                return element;
            }
        }
        return Result.valid();
    }

    private static Result checkCardinality(TagDefinition definition, SubmittedValue submitted) {
        boolean wantsMulti = definition.getCardinality() == TagCardinality.MULTI;
        if (wantsMulti && !submitted.isMulti()) {
            return Result.invalid(
                    "Tag '" + definition.getKey() + "' is multi-valued and requires a list of values");
        }
        if (!wantsMulti && submitted.isMulti()) {
            return Result.invalid(
                    "Tag '" + definition.getKey() + "' is single-valued and does not accept a list");
        }
        return Result.valid();
    }

    private static Result checkValue(TagDefinition definition, String value) {
        if (definition.getValueType() == TagValueType.ENUM) {
            if (!definition.getAllowedValues().contains(value)) {
                return Result.invalid("Tag '" + definition.getKey() + "' value '" + value
                        + "' is not one of the allowed values " + definition.getAllowedValues());
            }
            return Result.valid();
        }
        // STRING: an optional regex constrains the value; absent → any string is legal.
        String pattern = definition.getValuePattern();
        if (pattern != null && !pattern.isBlank()) {
            try {
                if (!Pattern.matches(pattern, value)) {
                    return Result.invalid("Tag '" + definition.getKey() + "' value '" + value
                            + "' does not match the required pattern");
                }
            } catch (PatternSyntaxException ex) {
                // A malformed stored pattern must not silently pass the value — fail closed.
                return Result.invalid(
                        "Tag '" + definition.getKey() + "' has an invalid value pattern");
            }
        }
        return Result.valid();
    }
}
