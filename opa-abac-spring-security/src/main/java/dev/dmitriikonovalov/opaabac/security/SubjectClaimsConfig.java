package dev.dmitriikonovalov.opaabac.security;

import java.util.List;
import java.util.Objects;

/**
 * Where {@link JwtClaimsSubjectExtractor} reads each piece of the subject from a JWT's claims.
 *
 * <p>Nothing about the Keycloak token shape is hardcoded in the extractor — it is all configuration,
 * so a different IdP is a config change, not a code change (per the pluggable-extractor pattern). Claim
 * paths support dotted nesting (e.g. {@code realm_access.roles}).
 *
 * @param idClaim         dotted path to the subject id (default {@code sub})
 * @param rolesClaim      dotted path to the roles array (default {@code realm_access.roles})
 * @param usernameClaim   dotted path to the username (default {@code preferred_username})
 * @param attributeClaims additional top-level claim names copied verbatim into subject attributes
 * @param validateExpiry  whether to reject a token whose {@code exp} is in the past (default true)
 */
public record SubjectClaimsConfig(
        String idClaim,
        String rolesClaim,
        String usernameClaim,
        List<String> attributeClaims,
        boolean validateExpiry) {

    public SubjectClaimsConfig {
        idClaim = blankToDefault(idClaim, "sub");
        rolesClaim = blankToDefault(rolesClaim, "realm_access.roles");
        usernameClaim = blankToDefault(usernameClaim, "preferred_username");
        attributeClaims = attributeClaims == null ? List.of() : List.copyOf(attributeClaims);
    }

    /** The Keycloak-shaped defaults with expiry validation on. */
    public static SubjectClaimsConfig defaults() {
        return new SubjectClaimsConfig("sub", "realm_access.roles", "preferred_username", List.of(), true);
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? Objects.requireNonNull(fallback) : value;
    }
}
