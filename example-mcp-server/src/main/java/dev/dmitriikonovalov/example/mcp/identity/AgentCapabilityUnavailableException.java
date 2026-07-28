package dev.dmitriikonovalov.example.mcp.identity;

/**
 * The agent's capability <strong>source</strong> was unavailable, so its capability is
 * <em>unknown</em>.
 *
 * <p>This is the third state of the tri-state contract, and the reason the contract has three states
 * at all: "unknown" must never be collapsed into "no capability" or, far worse, into "no
 * restriction". A caller fails closed on this exactly as it does on an authoritative-empty profile —
 * the two are <strong>identical to the caller</strong> and differ only in the log and the internal
 * error code, so the difference is diagnosable without becoming an oracle for whoever is probing.
 */
public class AgentCapabilityUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "agent-capability-unavailable";

    public AgentCapabilityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
