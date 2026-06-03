package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.TagCardinality;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.TagScope;
import dev.dmitriikonovalov.example.usermgmt.domain.TagValueType;
import dev.dmitriikonovalov.example.usermgmt.service.TagValueValidator;
import dev.dmitriikonovalov.example.usermgmt.service.TagValueValidator.SubmittedValue;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Ticket-1 validator cases (D6–D8): enum membership, STRING + regex, and cardinality. Pure unit tests —
 * no Spring, no DB. The same validator runs both on define (ticket 2) and on assignment (ticket 3).
 */
class TagValueValidatorTest {

    private final TagValueValidator validator = new TagValueValidator();

    private static TagDefinition enumSingle() {
        return new TagDefinition(
                UUID.randomUUID(), "sensitivity", TagScope.GLOBAL, null,
                TagValueType.ENUM, TagCardinality.SINGLE,
                List.of("public", "internal", "confidential"), null, true);
    }

    private static TagDefinition enumMulti() {
        return new TagDefinition(
                UUID.randomUUID(), "region", TagScope.GLOBAL, null,
                TagValueType.ENUM, TagCardinality.MULTI,
                List.of("emea", "amer", "apac"), null, true);
    }

    private static TagDefinition stringSingle(String pattern) {
        return new TagDefinition(
                UUID.randomUUID(), "cost-center", TagScope.TEAM, UUID.randomUUID(),
                TagValueType.STRING, TagCardinality.SINGLE, List.of(), pattern, false);
    }

    // --- D6: ENUM membership ---------------------------------------------------

    @Test
    void enumValueInSetIsValid() {
        var result = validator.validate(enumSingle(), SubmittedValue.ofSingle("internal"));
        assertThat(result.ok()).isTrue();
    }

    @Test
    void enumValueOutsideSetIsInvalid() {
        var result = validator.validate(enumSingle(), SubmittedValue.ofSingle("secret"));
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("sensitivity").contains("secret");
    }

    @Test
    void enumMultiAllElementsInSetIsValid() {
        var result = validator.validate(enumMulti(), SubmittedValue.ofMulti(List.of("emea", "amer")));
        assertThat(result.ok()).isTrue();
    }

    @Test
    void enumMultiAnyElementOutsideSetIsInvalid() {
        var result = validator.validate(enumMulti(), SubmittedValue.ofMulti(List.of("emea", "mars")));
        assertThat(result.ok()).isFalse();
    }

    // --- D7: STRING + optional regex ------------------------------------------

    @Test
    void stringWithoutPatternAcceptsAnyValue() {
        var result = validator.validate(stringSingle(null), SubmittedValue.ofSingle("anything-goes"));
        assertThat(result.ok()).isTrue();
    }

    @Test
    void stringMatchingPatternIsValid() {
        var result = validator.validate(
                stringSingle("CC-[0-9]{4}"), SubmittedValue.ofSingle("CC-1234"));
        assertThat(result.ok()).isTrue();
    }

    @Test
    void stringNotMatchingPatternIsInvalid() {
        var result = validator.validate(
                stringSingle("CC-[0-9]{4}"), SubmittedValue.ofSingle("nope"));
        assertThat(result.ok()).isFalse();
    }

    @Test
    void malformedStoredPatternFailsClosed() {
        // An invalid regex must not silently pass the value.
        var result = validator.validate(
                stringSingle("CC-[0-9"), SubmittedValue.ofSingle("CC-1234"));
        assertThat(result.ok()).isFalse();
    }

    // --- D8: cardinality -------------------------------------------------------

    @Test
    void singleGivenArrayIsInvalid() {
        var result = validator.validate(enumSingle(), SubmittedValue.ofMulti(List.of("public", "internal")));
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("single-valued");
    }

    @Test
    void multiGivenScalarIsInvalid() {
        var result = validator.validate(enumMulti(), SubmittedValue.ofSingle("emea"));
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("multi-valued");
    }

    // --- empty values ----------------------------------------------------------

    @Test
    void emptyScalarIsInvalid() {
        var result = validator.validate(stringSingle(null), SubmittedValue.ofSingle("  "));
        assertThat(result.ok()).isFalse();
    }

    @Test
    void emptyMultiIsInvalid() {
        var result = validator.validate(enumMulti(), SubmittedValue.ofMulti(List.of()));
        assertThat(result.ok()).isFalse();
    }
}
