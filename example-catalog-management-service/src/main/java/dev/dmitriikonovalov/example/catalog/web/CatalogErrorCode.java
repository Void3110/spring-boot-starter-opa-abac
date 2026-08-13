package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.opaabac.security.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * The catalog service's own {@link ApiErrorCode} vocabulary — the typed extension point for catalog
 * domain failures a client would branch on <em>within</em> a status, beyond the library's
 * {@code LibraryErrorCode}.
 *
 * <p>Most catalog errors still map cleanly to a library code — not-found → {@code RESOURCE_NOT_FOUND},
 * validation → {@code VALIDATION_FAILED}, an illegal tag assignment → {@code TAG_VALUE_ILLEGAL}, a
 * dictionary outage → {@code DEPENDENCY_UNAVAILABLE}, a deny → {@code ACCESS_DENIED}. Per ADR 0011 §4
 * (semantic granularity) we do <strong>not</strong> invent codes to fill this enum; a constant is added
 * only when a distinct, client-actionable catalog failure appears that no library code names.
 */
public enum CatalogErrorCode implements ApiErrorCode {

    /**
     * A public tag write would change the presence-or-value of an <b>operator-managed</b> key
     * (ADR 0030 §3). Its own code rather than the user-service's {@code TAG_DEFINITION_IMMUTABLE},
     * which names protection of a definition's <em>shape</em>: a caller — and the e2e strip cell —
     * must be able to tell which guard answered.
     */
    TAG_OPERATOR_MANAGED(HttpStatus.CONFLICT, "Operator-managed tag key");

    private final HttpStatus status;
    private final String title;

    CatalogErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
