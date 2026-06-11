package dev.dmitriikonovalov.example.catalog.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The service-wide paging convention (ADR 0012): every public list is paged with a <strong>fixed,
 * server-side total order</strong> — {@code createdAt ASC, id ASC} (both inherited from
 * {@code AbstractAuditableEntity}; the {@code id} tiebreaker makes same-timestamp rows deterministic).
 * Clients do not choose the sort; the paged library seam rejects an unsorted {@code Pageable} outright.
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
}
