package dev.dmitriikonovalov.example.mcp.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this server reads the delegation claim, under {@code example.mcp.identity}.
 *
 * <p>The claim <em>name</em> is configuration because the semantics — RFC 8693 {@code act}: who acted for
 * whom — outlive whatever a particular IdP happens to call it. The two limits are not tuning knobs but
 * fail-closed bounds: an identity is a small, well-known thing, and anything past these bounds is far more
 * likely to be an attack or a bug than a legitimate ten-deep delegation.
 */
@ConfigurationProperties("example.mcp.identity")
public class IdentityProperties {

    /**
     * Top-level claim carrying the delegation chain. Must also appear in
     * {@code opa.abac.subject.attribute-claims} so the starter's extractor copies it into the subject.
     */
    private String actorClaim = "act_chain";

    /** Maximum number of agents in the chain. Deeper → deny. */
    private int maxChainDepth = 4;

    /** Maximum serialized size of the claim, in characters. Larger → deny, before any deep traversal. */
    private int maxClaimLength = 2048;

    public String getActorClaim() {
        return actorClaim;
    }

    public void setActorClaim(String actorClaim) {
        this.actorClaim = actorClaim;
    }

    public int getMaxChainDepth() {
        return maxChainDepth;
    }

    public void setMaxChainDepth(int maxChainDepth) {
        this.maxChainDepth = maxChainDepth;
    }

    public int getMaxClaimLength() {
        return maxClaimLength;
    }

    public void setMaxClaimLength(int maxClaimLength) {
        this.maxClaimLength = maxClaimLength;
    }
}
