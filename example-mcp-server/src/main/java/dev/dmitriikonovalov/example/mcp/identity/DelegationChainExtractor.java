package dev.dmitriikonovalov.example.mcp.identity;

import dev.dmitriikonovalov.opaabac.core.AbacContext;

/**
 * Normalizes an authenticated caller into a {@link DelegationChain}.
 *
 * <h2>Contract: throw, never widen</h2>
 * There are exactly two honest outcomes and one forbidden one:
 * <ul>
 *   <li><strong>No actor claim</strong> → a principal-only chain. This is an ordinary human call, and it
 *       is the <em>widest</em> reading the tool-gate ever evaluates — still bounded by the principal's own
 *       ceiling, so it grants nothing.</li>
 *   <li><strong>A well-formed actor claim</strong> → principal + actor + the ordered chain.</li>
 *   <li><strong>Anything else</strong> → {@link DelegationChainException}. Never a principal-only
 *       fallback: an unreadable agent identity must not become a human one.</li>
 * </ul>
 *
 * <p>The seam takes the {@link AbacContext.Subject} the starter already built from the validated token,
 * rather than re-reading the token itself. That keeps <em>one</em> place deciding what a token means —
 * the principal is the same {@code sub} every other enforced path in this repo resolves, and the actor
 * claim rides in the subject's attributes because it is listed in
 * {@code opa.abac.subject.attribute-claims}. The shape is deliberately narrow enough that reading a real
 * RFC 8693 {@code act} claim later is an implementation change, not a caller change.
 */
@FunctionalInterface
public interface DelegationChainExtractor {

    /**
     * @param subject the authenticated caller, as resolved by the starter's subject extractor
     * @return the dual identity behind this call
     * @throws DelegationChainException when the identity cannot be read — callers deny
     */
    DelegationChain extract(AbacContext.Subject subject);
}
