package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Production-tier T2 (U3): the cross-service projection tolerates a user-service that predates the
 * {@code operatorManaged} flag.
 *
 * <p>The component is the {@code Boolean} <b>wrapper</b>, normalized in the compact constructor, and that
 * is load-bearing rather than stylistic: Jackson throws on a missing <em>primitive</em> record component,
 * so a primitive would turn an older user-service into a 5xx on every tag write instead of a degraded but
 * working read. Absent must mean {@code false} — the fail-safe direction, since a key nobody declared
 * operator-managed is simply an ordinary key.
 */
class TagDefinitionViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsTheFlagWhenPresent() {
        TagDefinitionView view = MAPPER.readValue(
                """
                {"key":"env","valueType":"ENUM","cardinality":"SINGLE",
                 "allowedValues":["production","staging","dev"],"operatorManaged":true}
                """,
                TagDefinitionView.class);

        assertThat(view.isOperatorManaged()).isTrue();
        assertThat(view.key()).isEqualTo("env");
        assertThat(view.allowedValues()).containsExactly("production", "staging", "dev");
    }

    @Test
    void readsFalseWhenTheFieldIsAbsent() {
        TagDefinitionView view = MAPPER.readValue(
                """
                {"key":"sensitivity","valueType":"ENUM","cardinality":"SINGLE",
                 "allowedValues":["public","internal"]}
                """,
                TagDefinitionView.class);

        assertThat(view.isOperatorManaged()).isFalse();
        assertThat(view.operatorManaged()).isFalse();
    }

    @Test
    void readsFalseWhenTheFieldIsExplicitlyNull() {
        TagDefinitionView view = MAPPER.readValue(
                """
                {"key":"region","valueType":"ENUM","cardinality":"MULTI",
                 "allowedValues":["emea"],"operatorManaged":null}
                """,
                TagDefinitionView.class);

        assertThat(view.isOperatorManaged()).isFalse();
    }

    @Test
    void thePreFlagArityIsUnmanaged() {
        assertThat(new TagDefinitionView("region", "ENUM", "MULTI", null, null).isOperatorManaged())
                .isFalse();
    }
}
