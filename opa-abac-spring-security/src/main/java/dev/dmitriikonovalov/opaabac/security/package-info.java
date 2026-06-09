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
 * <p>Enforcement: {@code @OpaPreAuthorize} + {@code OpaPreAuthorizeAuthorizationManager}, plus an opt-in
 * request-level {@code OpaAuthorizationManager}.
 *
 * <p>Error contract (RFC-7807): {@link dev.dmitriikonovalov.opaabac.security.ApiErrorCode} (the typed
 * machine-stable code interface), {@link dev.dmitriikonovalov.opaabac.security.LibraryErrorCode} (the
 * library's own codes), {@link dev.dmitriikonovalov.opaabac.security.ProblemDetail} (the
 * {@code application/problem+json} carrier), and
 * {@link dev.dmitriikonovalov.opaabac.security.AbstractProblemAdvice} /
 * {@link dev.dmitriikonovalov.opaabac.security.ProblemDetailFactory} (the reusable advice base + body
 * builder each service's exception handler extends — and the source of the
 * {@code AccessDeniedException} → {@code 403 ACCESS_DENIED} mapping).
 */
package dev.dmitriikonovalov.opaabac.security;
