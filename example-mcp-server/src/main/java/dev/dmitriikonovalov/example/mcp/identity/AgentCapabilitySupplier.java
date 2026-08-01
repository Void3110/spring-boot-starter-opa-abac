package dev.dmitriikonovalov.example.mcp.identity;

/**
 * Resolves the {@link AgentCapabilityProfile} that applies to an actor at decision time.
 *
 * <p>The seam that isolates the <em>source</em> of agent capability from the decision mechanics — the
 * same shape {@code RoleDefinitionSupplier} has for role definitions. The demo ships a
 * config-backed implementation; a real deployment swaps in one backed by an agent registry, and
 * nothing downstream changes.
 *
 * <h2>Tri-state contract (ADR 0014, applied to capability)</h2>
 * <ul>
 *   <li><strong>resolved</strong> — a profile. Narrow by it.</li>
 *   <li><strong>authoritative-empty</strong> — {@link AgentCapabilityProfile#empty()}: the actor is
 *       known and has zero capability. A <em>real answer</em>, and an unknown actor is treated as
 *       exactly this. The gate then denies every tool through its ordinary rules.</li>
 *   <li><strong>throws {@link AgentCapabilityUnavailableException}</strong> — the source was
 *       unavailable, so the answer is <em>unknown</em>. The caller MUST fail closed and must never
 *       substitute {@code empty()} silently: an outage is not zero capability, and conflating them
 *       would hide a broken dependency behind a plausible denial.</li>
 * </ul>
 *
 * <p>Both non-resolved states deny. They differ in the log and the internal error code and are
 * <strong>identical to the caller</strong>, so operators can tell them apart and a prober cannot.
 *
 * <p>Implementations must <strong>never return null</strong> — the absence of a profile is expressed
 * as {@link AgentCapabilityProfile#empty()}, so there is no third value state to mishandle.
 */
@FunctionalInterface
public interface AgentCapabilitySupplier {

    /**
     * The capability profile for {@code actorId}.
     *
     * @param actorId the agent making the call (never the principal)
     * @return the resolved profile, or {@link AgentCapabilityProfile#empty()} when the actor is known
     *     to have no capability — including when the actor is unknown. Never {@code null}.
     * @throws AgentCapabilityUnavailableException when the capability source was unavailable, so the
     *     answer is unknown — the caller denies and never falls back
     */
    AgentCapabilityProfile lookup(String actorId);
}
