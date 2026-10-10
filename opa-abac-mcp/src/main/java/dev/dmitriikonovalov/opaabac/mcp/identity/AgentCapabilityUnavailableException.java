package dev.dmitriikonovalov.opaabac.mcp.identity;

import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;

/**
 * The agent's capability <strong>source</strong> was unavailable, so its capability is
 * <em>unknown</em>.
 *
 * <p>This is the third state of the tri-state contract, and the reason the contract has three states
 * at all: "unknown" must never be collapsed into "no capability" or, far worse, into "no
 * restriction". A caller fails closed on this exactly as it does on an authoritative-empty profile —
 * the two are <strong>identical to the caller</strong> and differ only in the log and the internal
 * error code, so the difference is diagnosable without becoming an oracle for whoever is probing.
 *
 * <p>A member of the core library's "could not decide" family ({@link DecisionIndeterminateException},
 * ADR 0037): an adopter that only cares that no decision was possible catches the family, whichever
 * source failed. The tool-gate and the roster catch this type <em>before</em> the family, so the
 * capability outage keeps its own denial code.
 */
public class AgentCapabilityUnavailableException extends DecisionIndeterminateException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "agent-capability-unavailable";

    public AgentCapabilityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
