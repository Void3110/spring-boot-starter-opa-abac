package dev.dmitriikonovalov.example.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.TagCardinality;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.TagScope;
import dev.dmitriikonovalov.example.usermgmt.domain.TagValueType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Production-tier T1 (U2): {@code UserMgmtMapper.toDto} carries {@code operatorManaged} through to the
 * wire model in both directions. The internal projection ({@code GET /internal/tag-definitions}) maps
 * through this same method, so a drop here would silently disarm the catalog service's enforcement — it
 * cannot enforce what it never receives.
 */
class UserMgmtMapperTagDefinitionTest {

    private static TagDefinition definition(String key, boolean operatorManaged) {
        return new TagDefinition(
                UUID.randomUUID(),
                key,
                TagScope.GLOBAL,
                null,
                TagValueType.ENUM,
                TagCardinality.SINGLE,
                List.of("production", "staging", "dev"),
                null,
                true,
                operatorManaged);
    }

    @Test
    void carriesOperatorManagedTrue() {
        assertThat(UserMgmtMapper.toDto(definition("env", true)).getOperatorManaged()).isTrue();
    }

    @Test
    void carriesOperatorManagedFalse() {
        assertThat(UserMgmtMapper.toDto(definition("sensitivity", false)).getOperatorManaged()).isFalse();
    }

    @Test
    void theNineArgConstructorDefaultsToUnmanaged() {
        TagDefinition legacyShape = new TagDefinition(
                UUID.randomUUID(),
                "region",
                TagScope.GLOBAL,
                null,
                TagValueType.ENUM,
                TagCardinality.MULTI,
                List.of("emea", "amer", "apac"),
                null,
                true);

        assertThat(legacyShape.isOperatorManaged()).isFalse();
        assertThat(UserMgmtMapper.toDto(legacyShape).getOperatorManaged()).isFalse();
    }
}
