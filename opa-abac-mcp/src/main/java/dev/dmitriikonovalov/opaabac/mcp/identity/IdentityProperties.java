package dev.dmitriikonovalov.opaabac.mcp.identity;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this server reads the delegation claim, under {@code opa.abac.mcp.identity}.
 *
 * <p>The claim <em>name</em> is configuration because the semantics — RFC 8693 {@code act}: who acted for
 * whom — outlive whatever a particular IdP happens to call it. The two limits are not tuning knobs but
 * fail-closed bounds: an identity is a small, well-known thing, and anything past these bounds is far more
 * likely to be an attack or a bug than a legitimate ten-deep delegation.
 */
@ConfigurationProperties("opa.abac.mcp.identity")
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

    /**
     * OAuth client ids ({@code azp}) that are agents. A token from a listed client that carries no valid
     * actor claim is <strong>denied</strong> ({@code tool-gate-actor-required}) — a misconfigured agent
     * client, never evaluated as a human call. Empty (the default) ⇒ the check never runs. A non-empty list
     * requires {@code azp} in {@code opa.abac.subject.attribute-claims} (checked at startup).
     */
    private List<String> agentClients = new ArrayList<>();

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

    public List<String> getAgentClients() {
        return agentClients;
    }

    public void setAgentClients(List<String> agentClients) {
        this.agentClients = agentClients;
    }
}
