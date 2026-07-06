package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The 7.0.5 tag-requirement authoring contract (defense-in-depth for the catalog-filter High): a
 * malformed {@code required_tags}/{@code match_mode} is a 422, not a silently-never-matching role. The
 * primary control for the leak is the Rego {@code filter} tag conjunct; this validates the service seam.
 */
class RoleDefinitionTagContractTest {

    private static final Map<String, List<String>> READ = Map.of("catalog", List.of("READ"));
    private static final Map<String, List<String>> NO_DENIALS = Map.of();

    // --- valid tag requirements pass ------------------------------------------------

    @Test
    void anyOfWithTagsAccepted() {
        assertThatCode(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS, Map.of("region", List.of("emea", "apac")), "ANY_OF"))
                .doesNotThrowAnyException();
    }

    @Test
    void allOfWithTagsAccepted() {
        assertThatCode(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS,
                        Map.of("region", List.of("emea"), "status", List.of("active")), "ALL_OF"))
                .doesNotThrowAnyException();
    }

    @Test // blank matchMode is normalized to ANY_OF when tags are present — still valid
    void blankMatchModeWithTagsAccepted() {
        assertThatCode(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS, Map.of("region", List.of("emea")), null))
                .doesNotThrowAnyException();
    }

    @Test // an untagged role ignores matchMode entirely (normalized to null, never consulted)
    void untaggedRoleIgnoresMatchMode() {
        assertThatCode(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS, Map.of(), "GARBAGE"))
                .doesNotThrowAnyException();
        assertThatCode(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS, null, "GARBAGE"))
                .doesNotThrowAnyException();
    }

    // --- malformed tag requirements are rejected ------------------------------------

    @Test
    void unknownMatchModeWithTagsRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS, Map.of("region", List.of("emea")), "MOST_OF"))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("match_mode");
    }

    @Test
    void blankTagKeyRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS, Map.of("  ", List.of("emea")), "ANY_OF"))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("blank tag key");
    }

    @Test
    void emptyAcceptableValuesRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS, Map.of("region", List.of()), "ANY_OF"))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("no acceptable values");
    }

    @Test
    void blankAcceptableValueRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        10, READ, NO_DENIALS, Map.of("region", List.of("emea", " ")), "ALL_OF"))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("blank acceptable value");
    }
}
