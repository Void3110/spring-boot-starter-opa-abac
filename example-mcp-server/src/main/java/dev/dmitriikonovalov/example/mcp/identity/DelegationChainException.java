package dev.dmitriikonovalov.example.mcp.identity;

/**
 * The caller's identity could not be read.
 *
 * <p>Callers map this to <strong>deny</strong> — never to a principal-only fallback. That asymmetry is
 * deliberate and is the single most important thing about this type: an <em>absent</em> actor claim is an
 * ordinary human call, but a <em>malformed</em> one is not a human, it is a broken agent, and treating it
 * as a human would turn every parse bug into a way of shedding the agent's narrowing.
 */
public class DelegationChainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "delegation-chain-invalid";

    public DelegationChainException(String message) {
        super(message);
    }
}
