package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.example.catalog.config.IllegalTagAssignmentException;
import dev.dmitriikonovalov.example.catalog.config.TagAssignmentService;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionClient;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
import dev.dmitriikonovalov.example.catalog.config.TagOperatorManagedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Production-tier T2 (U4): the <b>delta-based</b> operator-managed rejection matrix.
 *
 * <p>Writes on this path are full-map replace, so "does the submitted map touch this key?" is a question
 * about the <em>delta</em>, not about presence. Assign, re-value and strip each move the key and are
 * rejected; an echo does not move it and passes. The echo case is the one that would be tempting to
 * reject "for safety" and must not be: a resource carrying {@code env} would then have every ordinary tag
 * edit frozen, and a frozen edit path is what sends people looking for a workaround.
 *
 * <p>The strip-via-empty-map case is the fail-open this test exists for: the empty-submission fast path
 * returns before any dictionary fetch, so it may only shortcut when there is nothing to protect either.
 */
class TagAssignmentOperatorManagedTest {

    private static final String ROOT_TYPE = "catalog";
    private static final String ROOT_ID = "11111111-1111-1111-1111-111111111111";

    private final TagAssignmentService service = new TagAssignmentService(
            new TagDefinitionClient(new ObjectMapper(), "http://unused", 100) {
                @Override
                public List<TagDefinitionView> fetchApplicable(String resourceType, String resourceId) {
                    return List.of(
                            new TagDefinitionView(
                                    "env", "ENUM", "SINGLE",
                                    List.of("production", "staging", "dev"), null, true),
                            new TagDefinitionView(
                                    "sensitivity", "ENUM", "SINGLE",
                                    List.of("public", "internal", "confidential"), null, false));
                }
            });

    private static Map<String, Object> tags(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private void write(Map<String, Object> submitted, Map<String, Object> current) {
        service.validateAndBuild(ROOT_TYPE, ROOT_ID, submitted, current);
    }

    // --- the three moves that are rejected -------------------------------------

    @Test
    void assignIsRejected() {
        assertThatThrownBy(() -> write(tags("env", "staging"), tags()))
                .isInstanceOf(TagOperatorManagedException.class)
                .hasMessageContaining("env");
    }

    @Test
    void reValueIsRejected() {
        assertThatThrownBy(() -> write(tags("env", "production"), tags("env", "staging")))
                .isInstanceOf(TagOperatorManagedException.class);
    }

    @Test
    void stripIsRejected() {
        assertThatThrownBy(() -> write(tags("sensitivity", "public"), tags("env", "staging")))
                .isInstanceOf(TagOperatorManagedException.class);
    }

    @Test
    void stripViaEmptyMapIsRejected() {
        assertThatThrownBy(() -> write(Map.of(), tags("env", "staging")))
                .isInstanceOf(TagOperatorManagedException.class);
    }

    @Test
    void stripViaNullMapIsRejected() {
        assertThatThrownBy(() -> write(null, tags("env", "staging")))
                .isInstanceOf(TagOperatorManagedException.class);
    }

    // --- the moves that are not moves ------------------------------------------

    @Test
    void echoPasses() {
        assertThatCode(() -> write(tags("env", "staging"), tags("env", "staging")))
                .doesNotThrowAnyException();
    }

    @Test
    void absentOnBothSidesPasses() {
        assertThatCode(() -> write(tags("sensitivity", "public"), tags("sensitivity", "internal")))
                .doesNotThrowAnyException();
    }

    @Test
    void aNonManagedKeyChangesFreelyAlongsideAnEchoedManagedKey() {
        var result = service.validateAndBuild(
                ROOT_TYPE,
                ROOT_ID,
                tags("env", "staging", "sensitivity", "confidential"),
                tags("env", "staging", "sensitivity", "public"));

        assertThat(result.asMap())
                .containsEntry("env", "staging")
                .containsEntry("sensitivity", "confidential");
    }

    @Test
    void bothMapsEmptyIsAnEmptyResultAndNeedsNoFetch() {
        assertThat(service.validateAndBuild(ROOT_TYPE, ROOT_ID, Map.of(), Map.of()).isEmpty()).isTrue();
    }

    // --- the 3-arg overload is create semantics (currentTags = {}) --------------

    @Test
    void theThreeArgOverloadRejectsAnOperatorManagedKeyOnCreate() {
        assertThatThrownBy(() -> service.validateAndBuild(ROOT_TYPE, ROOT_ID, tags("env", "dev")))
                .isInstanceOf(TagOperatorManagedException.class);
    }

    @Test
    void theThreeArgOverloadStillAcceptsOrdinaryKeys() {
        assertThat(service.validateAndBuild(ROOT_TYPE, ROOT_ID, tags("sensitivity", "public")).asMap())
                .containsEntry("sensitivity", "public");
    }

    // --- the operator's entry point bypasses the check, not the validation ------

    @Test
    void theOperatorPathWritesTheManagedKey() {
        assertThat(service.validateAsOperator(ROOT_TYPE, ROOT_ID, tags("env", "production")).asMap())
                .containsEntry("env", "production");
    }

    @Test
    void theOperatorPathStillValidatesValues() {
        assertThatThrownBy(() -> service.validateAsOperator(ROOT_TYPE, ROOT_ID, tags("env", "prod")))
                .isInstanceOf(IllegalTagAssignmentException.class);
        assertThatThrownBy(() -> service.validateAsOperator(ROOT_TYPE, ROOT_ID, tags("nope", "x")))
                .isInstanceOf(IllegalTagAssignmentException.class);
    }
}
