package dev.dmitriikonovalov.opaabac.security.directory;

import java.util.List;

/**
 * Searches the <strong>identity directory</strong> — every account the IdP knows, not just the profiles
 * provisioned into an application's own store — the seam behind "add a teammate who has never logged in"
 * (the user-directory port, ADR 0020).
 *
 * <h2>A pure search read-model</h2>
 * The port finds people; it never provisions, mutates, or joins to an application table. What a consumer
 * does with a match (provision on select, add to a team, ignore) is the application's concern.
 * Implementations may back this with Keycloak ({@code opa-abac-keycloak-directory}), LDAP, SCIM, or a
 * static list — the contract is "find people by query", nothing more.
 *
 * <h2>Fail-closed, no-oracle (the load-bearing invariant — ADR 0020 §8)</h2>
 * {@code search} returns an <strong>empty list on every non-affirmative outcome</strong> and never throws
 * past its own boundary: directory unreachable / timeout / auth failure → empty (WARN log only); a blank
 * {@code query} → empty <em>without contacting the directory</em> (never enumerate the realm); zero
 * matches → empty. An outage and a genuine empty are deliberately <strong>indistinguishable to the
 * caller</strong> — surfacing "the directory is down" (or how many accounts exist) would leak backend
 * state, so the identical empty is a no-oracle security property, not an error to fix. Outage vs empty
 * differs only in the implementation's WARN log.
 *
 * <p>Lives in {@code opa-abac-spring-security} next to
 * {@link dev.dmitriikonovalov.opaabac.security.ownership.ResourceOwnershipResolver} (the ADR-0019
 * precedent): an identity-adjacent seam, kept out of {@code opa-abac-core}.
 */
public interface UserDirectory {

    /**
     * Finds directory accounts matching {@code query}.
     *
     * @param query the search text (a username / name prefix or fragment); blank or {@code null} yields
     *     an empty list <em>without</em> a directory call
     * @param limit the maximum number of rows to return — implementations clamp it ({@code <= 0} → the
     *     default 20, {@code > 50} → the hard max 50; never unbounded)
     * @return the matching accounts, bounded by the clamped {@code limit}; <strong>empty on every error
     *     edge</strong> (unreachable / timeout / auth failure / blank query / no matches) — fail-closed,
     *     never throwing
     */
    List<DirectoryUser> search(String query, int limit);
}
