package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.opaabac.security.ApiErrorCode;

/**
 * The catalog service's own {@link ApiErrorCode} vocabulary — the typed extension point for catalog
 * domain failures a client would branch on <em>within</em> a status, beyond the library's
 * {@code LibraryErrorCode}.
 *
 * <p>Today it is intentionally empty: every error the catalog raises maps cleanly to a library code —
 * not-found → {@code RESOURCE_NOT_FOUND}, validation → {@code VALIDATION_FAILED}, an illegal tag
 * assignment → {@code TAG_VALUE_ILLEGAL}, a dictionary outage → {@code DEPENDENCY_UNAVAILABLE}, a deny →
 * {@code ACCESS_DENIED}. Per ADR 0011 §4 (semantic granularity) we do <strong>not</strong> invent codes
 * to fill it; a constant is added only when a distinct, client-actionable catalog failure appears that no
 * library code names. The enum exists so that extension is a one-line addition implementing the same
 * {@code ApiErrorCode} interface the advice already routes.
 */
public enum CatalogErrorCode implements ApiErrorCode {
    ; // no catalog-specific codes today — every failure maps to a LibraryErrorCode

    @Override
    public String code() {
        return name();
    }
}
