package dev.dmitriikonovalov.example.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.RoleDefinitionService;
import dev.dmitriikonovalov.example.usermgmt.web.InternalBootstrapController.EnsureUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * U4 (DIRECTORY-QUERY-FILTERS) — the bootstrap {@code ensureUser} upsert logic: an existing subject
 * with a <em>changed</em> {@code displayName} converges to the new value on the <em>same</em> row
 * (same id, one save); an identical re-post is a <b>no-op</b> (no save at all); a miss creates as
 * before. Only {@code displayName} is ever written — the subject is never re-pointed. Plain unit
 * test over stubbed repositories (no Spring context).
 */
class InternalBootstrapUpsertTest {

    private final UserRepository users = mock(UserRepository.class);

    private final InternalBootstrapController controller = new InternalBootstrapController(
            users,
            mock(TeamRepository.class),
            mock(TeamMembershipRepository.class),
            mock(RoleDefinitionRepository.class),
            mock(RoleDefinitionService.class));

    private final UUID existingId = UUID.randomUUID();
    private final User existing = new User(existingId, "sub-seeded", "Old Name");

    @Test
    void changedDisplayNameConvergesOnTheSameRow() {
        when(users.findBySubject("sub-seeded")).thenReturn(Optional.of(existing));
        when(users.save(existing)).thenReturn(existing);

        var response = controller.ensureUser(new EnsureUser("sub-seeded", "New Name"));

        assertThat(response.getBody()).containsEntry("userId", existingId);
        assertThat(existing.getDisplayName()).isEqualTo("New Name");
        assertThat(existing.getSubject()).isEqualTo("sub-seeded"); // never re-pointed
        verify(users).save(existing); // the update — not a create (no new entity saved)
    }

    @Test
    void identicalRepostIsANoOp() {
        when(users.findBySubject("sub-seeded")).thenReturn(Optional.of(existing));

        var response = controller.ensureUser(new EnsureUser("sub-seeded", "Old Name"));

        assertThat(response.getBody()).containsEntry("userId", existingId);
        assertThat(existing.getDisplayName()).isEqualTo("Old Name");
        verify(users, never()).save(any()); // converged already — nothing written
    }

    @Test
    void nullDisplayNameKeepsTheStoredName() {
        when(users.findBySubject("sub-seeded")).thenReturn(Optional.of(existing));

        controller.ensureUser(new EnsureUser("sub-seeded", null));

        assertThat(existing.getDisplayName()).isEqualTo("Old Name");
        verify(users, never()).save(any());
    }

    @Test
    void missStillCreates() {
        when(users.findBySubject("sub-new")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = controller.ensureUser(new EnsureUser("sub-new", "Fresh"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("userId")).isNotNull();
        verify(users).save(any(User.class));
    }
}
