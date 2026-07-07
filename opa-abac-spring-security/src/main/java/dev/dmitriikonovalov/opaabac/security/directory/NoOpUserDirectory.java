package dev.dmitriikonovalov.opaabac.security.directory;

import java.util.List;

/**
 * The no-directory default: every search returns an empty list. A bare adopter (no directory module, no
 * opt-in) gets this via the starter's {@code @ConditionalOnMissingBean} fallback, so a
 * {@link UserDirectory} injection point always resolves and the zero-config behavior is the fail-closed
 * empty — the lean-starter promise (ADR 0020 §3).
 */
public final class NoOpUserDirectory implements UserDirectory {

    @Override
    public List<DirectoryUser> search(String query, int limit) {
        return List.of();
    }
}
