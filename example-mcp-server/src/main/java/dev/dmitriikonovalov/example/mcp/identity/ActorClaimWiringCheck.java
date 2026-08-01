package dev.dmitriikonovalov.example.mcp.identity;

import dev.dmitriikonovalov.opaabac.autoconfigure.OpaAbacProperties;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;

/**
 * Fails startup unless the configured actor claim is one the starter actually copies into the subject.
 *
 * <h2>Why this is a security control and not a convenience</h2>
 * {@link ClaimDelegationChainExtractor} reads the delegation claim out of
 * {@code AbacContext.Subject#attributes()}, which the starter populates only for claim names listed in
 * {@code opa.abac.subject.attribute-claims}. If those two settings ever drift — someone renames
 * {@code example.mcp.identity.actor-claim}, or trims the starter's list — the claim simply is not there,
 * and {@link ClaimDelegationChainExtractor} reads "no actor claim", which means <strong>ordinary human
 * call</strong>.
 *
 * <p>That is a <em>silent widening</em>: every agent would quietly evaluate at its principal's full
 * ceiling with the agent narrowing removed, and nothing would fail, log, or look wrong. It is precisely
 * the failure mode this slice exists to prevent, arriving through the back door of a config typo. So the
 * coupling is made explicit and checked once, at startup, where it is loud and cheap.
 */
public class ActorClaimWiringCheck implements InitializingBean {

    private final IdentityProperties identityProperties;
    private final OpaAbacProperties starterProperties;

    public ActorClaimWiringCheck(
            IdentityProperties identityProperties, OpaAbacProperties starterProperties) {
        this.identityProperties = identityProperties;
        this.starterProperties = starterProperties;
    }

    @Override
    public void afterPropertiesSet() {
        String actorClaim = identityProperties.getActorClaim();
        List<String> copiedClaims = starterProperties.getSubject().getAttributeClaims();

        if (actorClaim == null || actorClaim.isBlank()) {
            throw new IllegalStateException(
                    "example.mcp.identity.actor-claim is blank; the delegation claim cannot be read.");
        }
        if (!copiedClaims.contains(actorClaim)) {
            throw new IllegalStateException(
                    "example.mcp.identity.actor-claim='" + actorClaim + "' is not listed in "
                            + "opa.abac.subject.attribute-claims=" + copiedClaims
                            + ". The claim would never reach the subject, so every agent call would be "
                            + "read as an ordinary human call — a silent widening. Add it to both.");
        }
    }
}
