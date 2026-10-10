package dev.dmitriikonovalov.opaabac.mcp.identity;

import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Fails startup unless the configured actor claim is one the starter actually copies into the subject.
 *
 * <h2>Why this is a security control and not a convenience</h2>
 * {@link ClaimDelegationChainExtractor} reads the delegation claim out of
 * {@code AbacContext.Subject#attributes()}, which the starter populates only for claim names listed in
 * {@code opa.abac.subject.attribute-claims}. If those two settings ever drift — someone renames
 * {@code opa.abac.mcp.identity.actor-claim}, or trims the starter's list — the claim simply is not there,
 * and {@link ClaimDelegationChainExtractor} reads "no actor claim", which means <strong>ordinary human
 * call</strong>.
 *
 * <p>That is a <em>silent widening</em>: every agent would quietly evaluate at its principal's full
 * ceiling with the agent narrowing removed, and nothing would fail, log, or look wrong. It is precisely
 * the failure mode the tool-gate exists to prevent, arriving through the back door of a config typo. So
 * the coupling is made explicit and checked once, at startup, where it is loud and cheap.
 *
 * <h2>Why it takes the copied claims as a plain list</h2>
 * This module cannot see the starter's properties type, and must not: the claim list is passed in by
 * whoever wires the check — read from the environment, so the check never depends on the starter's
 * properties bean.
 */
public class ActorClaimWiringCheck implements SmartInitializingSingleton {

    private final IdentityProperties identityProperties;
    private final List<String> copiedClaims;

    /**
     * @param identityProperties the delegation-claim settings ({@code opa.abac.mcp.identity.*})
     * @param copiedClaims       the claim names the starter copies into the subject
     *                           ({@code opa.abac.subject.attribute-claims}); {@code null} reads as empty
     */
    public ActorClaimWiringCheck(IdentityProperties identityProperties, List<String> copiedClaims) {
        this.identityProperties = identityProperties;
        this.copiedClaims = copiedClaims == null ? List.of() : List.copyOf(copiedClaims);
    }

    /**
     * Runs once every singleton exists — a {@link SmartInitializingSingleton} callback, so the check stays
     * eager under {@code spring.main.lazy-initialization=true} (Boot exempts these beans from lazy
     * initialization; an {@code InitializingBean} nobody injects would simply never be created).
     */
    @Override
    public void afterSingletonsInstantiated() {
        String actorClaim = identityProperties.getActorClaim();

        if (actorClaim == null || actorClaim.isBlank()) {
            throw new IllegalStateException(
                    "opa.abac.mcp.identity.actor-claim is blank; the delegation claim cannot be read.");
        }
        if (!copiedClaims.contains(actorClaim)) {
            throw new IllegalStateException(
                    "opa.abac.mcp.identity.actor-claim='" + actorClaim + "' is not listed in "
                            + "opa.abac.subject.attribute-claims=" + copiedClaims
                            + ". The claim would never reach the subject, so every agent call would be "
                            + "read as an ordinary human call — a silent widening. Add it to both.");
        }
    }
}
