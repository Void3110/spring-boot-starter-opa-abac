/**
 * Spring Security integration for OPA-backed ABAC.
 *
 * <p>Planned surface (built up in later phases):
 * <ul>
 *   <li>{@code OpaAuthorizationManager} — {@code AuthorizationManager<RequestAuthorizationContext>}</li>
 *   <li>{@code @OpaPreAuthorize} — method-level pre-authorization</li>
 *   <li>{@code AbacContextExtractor} — pluggable JWT/token → {@code AbacContext} extraction</li>
 *   <li>{@code AbacFilter} — populates the request-scoped ABAC context</li>
 * </ul>
 */
package dev.dmitriikonovalov.opaabac.security;
