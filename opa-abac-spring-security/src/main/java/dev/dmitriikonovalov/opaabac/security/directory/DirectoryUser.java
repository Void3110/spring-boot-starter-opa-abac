package dev.dmitriikonovalov.opaabac.security.directory;

/**
 * One identity-directory account, as much as a search may disclose: the stable identifier and a
 * renderable name. This record is the <strong>disclosure ceiling</strong> (ADR 0020 §7) — no
 * implementation can widen a search result past these two fields, so email, roles, and attributes stay
 * unexposed by construction. Do not add fields: the privacy control is the type, not endpoint filtering.
 *
 * @param subject the directory's stable account identifier (the IdP {@code sub} / username — the join
 *     key an application provisions against)
 * @param displayName a human-renderable name; implementations fall back to {@code subject} when the
 *     directory holds none, so this is always renderable
 */
public record DirectoryUser(String subject, String displayName) {}
