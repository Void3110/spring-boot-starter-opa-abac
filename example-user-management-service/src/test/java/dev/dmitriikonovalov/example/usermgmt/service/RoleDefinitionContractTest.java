package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Phase-6.5 authoring contract, unit-level (U4–U7) — plain JUnit on the static validation. */
class RoleDefinitionContractTest {

    private static final Map<String, List<String>> NO_DENIALS = Map.of();

    // --- U4: the authorable ladder ------------------------------------------------

    @Test
    void missingRoleLevelRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        null, Map.of("catalog", List.of("READ")), NO_DENIALS))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("roleLevel");
    }

    @Test
    void offLadderLevelsRejected() {
        for (int level : new int[] {15, 40, 0, -10, 26}) {
            assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                            level, Map.of("catalog", List.of("READ")), NO_DENIALS))
                    .as("level %d", level)
                    .isInstanceOf(RoleDefinitionInvalidException.class);
        }
    }

    @Test
    void everyAuthorableLevelAccepted() {
        for (int level : new int[] {10, 20, 25, 30}) {
            assertThatCode(() -> RoleDefinitionService.validateContract(
                            level, Map.of("catalog", List.of("READ")), NO_DENIALS))
                    .as("level %d", level)
                    .doesNotThrowAnyException();
        }
    }

    // --- U5: only category tokens pass the boundary --------------------------------

    @Test
    void flatVerbRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        20, Map.of("catalog", List.of("read")), NO_DENIALS))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("'read'");
    }

    @Test
    void fineActionAsGrantRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        20, Map.of("catalog", List.of("VIEW")), NO_DENIALS))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("'VIEW'");
    }

    // --- U6: the level ceiling ------------------------------------------------------

    @Test
    void grantBelowAdministratorRejected() {
        for (int level : new int[] {10, 20, 25}) {
            assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                            level, Map.of("catalog", List.of("GRANT")), NO_DENIALS))
                    .as("GRANT at level %d", level)
                    .isInstanceOf(RoleDefinitionInvalidException.class)
                    .hasMessageContaining("ceiling");
        }
    }

    @Test
    void writeAtReaderLevelRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        10, Map.of("catalog", List.of("WRITE")), NO_DENIALS))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("ceiling");
    }

    @Test
    void administratorMayGrantEverything() {
        assertThatCode(() -> RoleDefinitionService.validateContract(
                        30, Map.of("catalog", List.of("READ", "WRITE", "TAG", "GRANT")), NO_DENIALS))
                .doesNotThrowAnyException();
    }

    // --- U7: strict denial validation -------------------------------------------------

    @Test
    void denialOfUngrantedActionRejected() {
        // READ grants view/list — "delete" was never granted, so denying it is rejected.
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        20,
                        Map.of("catalog", List.of("READ")),
                        Map.of("catalog", List.of("delete"))))
                .isInstanceOf(RoleDefinitionInvalidException.class)
                .hasMessageContaining("denied action 'delete'");
    }

    @Test
    void denialSubtractingFromGrantsAccepted() {
        assertThatCode(() -> RoleDefinitionService.validateContract(
                        20,
                        Map.of("catalog", List.of("READ", "WRITE")),
                        Map.of("catalog", List.of("delete"))))
                .doesNotThrowAnyException();
    }

    @Test
    void denialOnTypeWithNoGrantsRejected() {
        assertThatThrownBy(() -> RoleDefinitionService.validateContract(
                        20,
                        Map.of("catalog", List.of("READ")),
                        Map.of("product", List.of("view"))))
                .isInstanceOf(RoleDefinitionInvalidException.class);
    }

    @Test
    void denialValidatesAgainstWildcardGrant() {
        // A concrete-type denial subtracts from the "*" grant when no concrete grant key exists —
        // the same lookup the policy's tokens_for performs.
        assertThatCode(() -> RoleDefinitionService.validateContract(
                        20,
                        Map.of("*", List.of("READ", "WRITE")),
                        Map.of("catalog", List.of("delete"))))
                .doesNotThrowAnyException();
    }
}
