package dev.dmitriikonovalov.example.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * U1 (DIRECTORY-QUERY-FILTERS) — the {@code ?subject} exact-match branch of {@code listUsers}:
 * present+matched → a one-item page; present+unmatched → an <em>empty</em> page ({@code count} 0,
 * never 404, never the full list); absent/blank → the unchanged paged {@code findAll}. Plain unit
 * test over a stubbed repository (no Spring context) — the branch decision, not the wire.
 */
class UserControllerFilterTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserController controller = new UserController(users);

    private final User match = new User(UUID.randomUUID(), "sub-known", "Known");

    // U1a — a matched subject is a one-item page; the findAll path is never consulted.
    @Test
    void matchedSubjectIsOneItemPage() {
        when(users.findBySubject("sub-known")).thenReturn(Optional.of(match));

        var page = controller.listUsers("sub-known", 0, 20).getBody();

        assertThat(page).isNotNull();
        assertThat(page.getCount()).isEqualTo(1);
        assertThat(page.getItems()).singleElement()
                .satisfies(u -> assertThat(u.getSubject()).isEqualTo("sub-known"));
        verify(users, never()).findAll(any(Pageable.class));
    }

    // U1b — an unmatched subject is an EMPTY page (count 0): not a 404, not a full-list fallthrough.
    @Test
    void unmatchedSubjectIsEmptyPageNeverTheFullList() {
        when(users.findBySubject("sub-missing")).thenReturn(Optional.empty());

        var page = controller.listUsers("sub-missing", 0, 20).getBody();

        assertThat(page).isNotNull();
        assertThat(page.getCount()).isZero();
        assertThat(page.getItems()).isEmpty();
        verify(users, never()).findAll(any(Pageable.class));
    }

    // U1c — absent or blank falls through to the unchanged paged findAll (the additive-branch pin).
    @Test
    void absentOrBlankSubjectFallsThroughToPagedFindAll() {
        var other = new User(UUID.randomUUID(), "sub-other", "Other");
        when(users.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(match, other), PageDefaults.pageRequest(0, 20), 2));

        for (String subject : new String[] {null, "", "   "}) {
            var page = controller.listUsers(subject, 0, 20).getBody();
            assertThat(page).isNotNull();
            assertThat(page.getCount()).isEqualTo(2);
            assertThat(page.getItems()).hasSize(2);
        }
        verify(users, never()).findBySubject(any());
    }

    // The filter branch keeps the envelope's past-the-end semantic (ADR 0012): a window past the
    // single result is an empty page with the exact count — the row never repeats across pages.
    @Test
    void pageWindowPastTheSingleMatchIsEmptyWithExactCount() {
        when(users.findBySubject("sub-known")).thenReturn(Optional.of(match));

        var page = controller.listUsers("sub-known", 1, 20).getBody();

        assertThat(page).isNotNull();
        assertThat(page.getCount()).isEqualTo(1);
        assertThat(page.getItems()).isEmpty();
        assertThat(page.getPage()).isEqualTo(1);
    }
}
