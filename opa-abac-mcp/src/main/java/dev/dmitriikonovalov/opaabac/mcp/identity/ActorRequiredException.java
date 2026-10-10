package dev.dmitriikonovalov.opaabac.mcp.identity;

/**
 * A token from an OAuth client listed in {@code opa.abac.mcp.identity.agent-clients} carries no actor
 * claim: a <strong>misconfigured agent client</strong> (its IdP mapper is missing), not a human.
 *
 * <p>Callers map it to <strong>deny</strong> with {@code tool-gate-actor-required}. It is a deterministic
 * answer — the token says who minted it and what it lacks — so it is deliberately <em>not</em> a
 * {@code DecisionIndeterminateException}: nothing here "could not decide", and retrying changes nothing.
 * The roster, which cannot deny, degrades to the unfiltered list for it (an identity edge outside the
 * batch) while the call-time gate denies every tool.
 *
 * <p>The message names the client id only — never a claim value or token content.
 */
public class ActorRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String clientId;

    public ActorRequiredException(String clientId) {
        super("client '" + clientId + "' is listed in opa.abac.mcp.identity.agent-clients but its token "
                + "carries no actor claim");
        this.clientId = clientId;
    }

    /** The {@code azp} that is listed as an agent client. */
    public String clientId() {
        return clientId;
    }
}
