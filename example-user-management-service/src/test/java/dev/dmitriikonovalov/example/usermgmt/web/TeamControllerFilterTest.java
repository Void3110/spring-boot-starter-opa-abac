package dev.dmitriikonovalov.example.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.service.CallerIdentity;
import dev.dmitriikonovalov.example.usermgmt.service.TeamService;
import dev.dmitriikonovalov.opaabac.security.ownership.ResourceOwnershipResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * U2 (DIRECTORY-QUERY-FILTERS) — the {@code (?targetType, ?targetId)} composite-key branch of
 * {@code listTeams}: both present → a one-item page (empty on a miss, never the full list); both
 * absent → the unchanged paged {@code findAll}; <em>exactly one</em> → a validation error (→ 400
 * {@code VALIDATION_FAILED} through the existing advice) — a half-specified key must never degrade
 * to a whole-collection scan. Plain unit test over stubbed collaborators (no Spring context).
 */
class TeamControllerFilterTest {

    private final TeamRepository teams = mock(TeamRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ResourceOwnershipResolver> ownership = mock(ObjectProvider.class);

    private final TeamController controller = new TeamController(
            teams, mock(TeamService.class), mock(CallerIdentity.class), ownership);

    private final UUID targetId = UUID.randomUUID();
    private final Team governing = new Team(UUID.randomUUID(), "Owners", "catalog", targetId);

    // U2a — both present, matched → the governing team as a one-item page; findAll never consulted.
    @Test
    void matchedPairIsOneItemPage() {
        when(teams.findByTargetTypeAndTargetId("catalog", targetId))
                .thenReturn(Optional.of(governing));

        var page = controller.listTeams("catalog", targetId, 0, 20).getBody();

        assertThat(page).isNotNull();
        assertThat(page.getCount()).isEqualTo(1);
        assertThat(page.getItems()).singleElement().satisfies(t -> {
            assertThat(t.getId()).isEqualTo(governing.getId());
            assertThat(t.getTargetId()).isEqualTo(targetId);
        });
        verify(teams, never()).findAll(any(Pageable.class));
    }

    // U2b — both present, unmatched → an EMPTY page (count 0), not a fallthrough to the full list.
    @Test
    void unmatchedPairIsEmptyPageNeverTheFullList() {
        when(teams.findByTargetTypeAndTargetId(any(), any())).thenReturn(Optional.empty());

        var page = controller.listTeams("catalog", UUID.randomUUID(), 0, 20).getBody();

        assertThat(page).isNotNull();
        assertThat(page.getCount()).isZero();
        assertThat(page.getItems()).isEmpty();
        verify(teams, never()).findAll(any(Pageable.class));
    }

    // U2c — exactly one of the pair is a validation error (400), NEVER a full-list fallthrough.
    // A blank targetType counts as absent, so blank+id is half-specified too.
    @Test
    void halfSpecifiedPairIsValidationErrorNeverAFallthrough() {
        assertThatThrownBy(() -> controller.listTeams("catalog", null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> controller.listTeams(null, targetId, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.listTeams("   ", targetId, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);

        verify(teams, never()).findAll(any(Pageable.class));
        verify(teams, never()).findByTargetTypeAndTargetId(any(), any());
    }

    // U2d — both absent (or blank type alone) falls through to the unchanged paged findAll.
    @Test
    void bothAbsentFallsThroughToPagedFindAll() {
        var other = new Team(UUID.randomUUID(), "Other", "catalog", UUID.randomUUID());
        when(teams.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(governing, other), PageDefaults.pageRequest(0, 20), 2));

        for (String targetType : new String[] {null, "", "   "}) {
            var page = controller.listTeams(targetType, null, 0, 20).getBody();
            assertThat(page).isNotNull();
            assertThat(page.getCount()).isEqualTo(2);
            assertThat(page.getItems()).hasSize(2);
        }
        verify(teams, never()).findByTargetTypeAndTargetId(any(), any());
    }
}
