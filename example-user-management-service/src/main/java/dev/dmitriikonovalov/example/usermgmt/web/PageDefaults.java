package dev.dmitriikonovalov.example.usermgmt.web;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The service-wide paging convention (ADR 0012): every public list is paged with a <strong>fixed,
 * server-side total order</strong> — {@code createdAt ASC, id ASC} (both inherited from
 * {@code AbstractAuditableEntity}; the {@code id} tiebreaker makes same-timestamp rows deterministic).
 * Clients do not choose the sort. Each service owns its copy of this tiny constant so the two
 * example builds stay independent (the same reasoning as the per-spec envelope definition).
 */
final class PageDefaults {

    /** The fixed total order behind every paged list in this service. */
    static final Sort DEFAULT_ORDER =
            Sort.by("createdAt").ascending().and(Sort.by("id").ascending());

    private PageDefaults() {
    }

    /** The {@code PageRequest} for the spec's 0-based {@code page}/{@code perPage}, fixed order applied. */
    static PageRequest pageRequest(int page, int perPage) {
        return PageRequest.of(page, perPage, DEFAULT_ORDER);
    }

    /**
     * A unique-key filtered lookup as a page in the same envelope (the DIRECTORY-QUERY-FILTERS
     * branch): the match, when present, is the single row of page 0; {@code count} is exact (0 or 1).
     * A window past the single result is {@code 200} + empty {@code items} + the exact {@code count}
     * — the ADR 0012 past-the-end semantic, same as any other paged list.
     */
    static <T> Page<T> onePage(Optional<T> match, PageRequest request) {
        List<T> content = request.getPageNumber() == 0
                ? match.map(List::of).orElse(List.of())
                : List.of();
        return new PageImpl<>(content, request, match.isPresent() ? 1 : 0);
    }
}
