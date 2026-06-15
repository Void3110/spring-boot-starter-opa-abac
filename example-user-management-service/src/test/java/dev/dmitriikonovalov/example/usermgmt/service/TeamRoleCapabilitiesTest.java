package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import org.junit.jupiter.api.Test;

/**
 * U1–U2 (Phase 6.7) — the management ladder projects each role <b>code</b> into the coarse category
 * tokens {@code team.rego} expands. Owner/admin carry {@code [READ, CONTROL, TAG]}; senior {@code [READ,
 * CONTROL]} (no TAG → no define-tags); member/reader/custom {@code [READ]} (list-members only). The two
 * owner-only verbs (define-roles, transfer-ownership) are NOT tokens here — they are the owner-only
 * fence in {@code team.rego}.
 */
class TeamRoleCapabilitiesTest {

    @Test // U1 — owner and administrator carry the full delegatable ladder
    void ownerAndAdministratorCarryReadControlTag() {
        assertThat(TeamRoleCapabilities.forCode(SystemRoles.OWNER))
                .containsExactly("READ", "CONTROL", "TAG");
        assertThat(TeamRoleCapabilities.forCode(SystemRoles.ADMINISTRATOR))
                .containsExactly("READ", "CONTROL", "TAG");
    }

    @Test // U1 — senior manages members (CONTROL) but cannot curate the dictionary (no TAG)
    void seniorCarriesReadControlButNotTag() {
        assertThat(TeamRoleCapabilities.forCode(SystemRoles.SENIOR))
                .containsExactly("READ", "CONTROL")
                .doesNotContain("TAG");
    }

    @Test // U1 — member and reader carry READ only (list-members, nothing wider)
    void memberAndReaderCarryReadOnly() {
        assertThat(TeamRoleCapabilities.forCode(SystemRoles.MEMBER)).containsExactly("READ");
        assertThat(TeamRoleCapabilities.forCode(SystemRoles.READER)).containsExactly("READ");
    }

    @Test // U2 — a custom (non-system) code is management-incapable: READ only (the I12 default)
    void customCodeProjectsToReadOnly() {
        assertThat(TeamRoleCapabilities.forCode("lead")).containsExactly("READ");
        assertThat(TeamRoleCapabilities.forCode("anything-custom")).containsExactly("READ");
    }

    @Test // the ladder never leaks the two owner-only fence verbs as tokens
    void noCodeCarriesTheOwnerOnlyFenceVerbsAsTokens() {
        for (String code : SystemRoles.ALL_CODES) {
            assertThat(TeamRoleCapabilities.forCode(code))
                    .as(code)
                    .doesNotContain("define-roles", "transfer-ownership", "GRANT", "WRITE");
        }
    }
}
