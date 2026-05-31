/**
 * Spring Security integration for OPA-backed ABAC.
 *
 * <p>Identity extraction (this module):
 * <ul>
 *   <li>{@link dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor} — pluggable token/request →
 *       {@code AbacContext.Subject} extraction, with {@code JwtClaimsSubjectExtractor} as the default
 *       (gateway-trusted, no signature verification)</li>
 *   <li>{@link dev.dmitriikonovalov.opaabac.security.AbacAuthentication} — an
 *       {@code AbstractAuthenticationToken} carrying the subject; authorities {@code ROLE_<role>}</li>
 *   <li>{@link dev.dmitriikonovalov.opaabac.security.AbacFilter} — populates the
 *       {@code SecurityContextHolder} from the request</li>
 * </ul>
 *
 * <p>Enforcement (added in the next ticket): {@code @OpaPreAuthorize} +
 * {@code OpaPreAuthorizeAuthorizationManager}, plus an opt-in request-level
 * {@code OpaAuthorizationManager}.
 */
package dev.dmitriikonovalov.opaabac.security;
