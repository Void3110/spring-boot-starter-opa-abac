package dev.dmitriikonovalov.example.usermgmt.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.EffectiveRoleService;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * B2 (QA U14): {@link TeamRoleDefinitionSupplier} maps a data-access outage to
 * {@link RoleResolutionException} (the tri-state contract) while keeping the authoritative no-role cases
 * as {@link Optional#empty()}.
 */
class TeamRoleDefinitionSupplierTest {

    private final UserRepository users = mock(UserRepository.class);
    private final EffectiveRoleService effectiveRoles = mock(EffectiveRoleService.class);
    private final TeamRoleDefinitionSupplier supplier = new TeamRoleDefinitionSupplier(users, effectiveRoles);

    private static final String TEAM_ID = "11111111-1111-1111-1111-111111111111";

    @Test // U14 — a repository data-access failure → throws RoleResolutionException (outage, not no-role)
    void dataAccessFailure_throwsRoleResolutionException() {
        when(users.findBySubject(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> supplier.lookup("sub-1", "team", TEAM_ID))
                .isInstanceOf(RoleResolutionException.class);
    }

    @Test // U14 — no user maps to the subject → authoritative no-role (Optional.empty())
    void noUser_isEmpty() {
        when(users.findBySubject("sub-1")).thenReturn(Optional.empty());
        assertThat(supplier.lookup("sub-1", "team", TEAM_ID)).isEmpty();
    }

    @Test // U14 — user exists but is not a member → authoritative no-role (Optional.empty())
    void notAMember_isEmpty() {
        User user = new User(UUID.randomUUID(), "sub-1", "Alice");
        when(users.findBySubject("sub-1")).thenReturn(Optional.of(user));
        when(effectiveRoles.managementRole(any(), any())).thenReturn(Optional.empty());

        assertThat(supplier.lookup("sub-1", "team", TEAM_ID)).isEmpty();
    }

    @Test // the no-role guards (not a team / unparseable id) stay empty, never throw
    void notATeamOrUnparseableId_isEmpty() {
        assertThat(supplier.lookup("sub-1", "catalog", TEAM_ID)).isEmpty(); // not a team
        assertThat(supplier.lookup("sub-1", "team", "not-a-uuid")).isEmpty(); // unparseable id
        assertThat(supplier.lookup("sub-1", "team", null)).isEmpty(); // no id
    }
}
