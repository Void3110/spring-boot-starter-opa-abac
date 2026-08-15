package dev.dmitriikonovalov.opaabac.core;

/**
 * A single authorization decision, optionally carrying a structured {@link DenyReason}.
 *
 * <p>The envelope {@link OpaClient#allow(AbacContext)} returns as a bare {@code boolean}, widened
 * <strong>additively</strong> (ADR 0030 §6): {@code denyReason} is {@code null} for every decision the
 * library made before this existed, so an allow and a plain deny are exactly what they always were.
 *
 * <p><strong>A reason only ever accompanies a deny.</strong> An allow needs no explanation, and a
 * document carrying both is contradictory — the parse drops the reason rather than pass on a mixed
 * signal.
 *
 * @param allow      whether the policy allows the action
 * @param denyReason the structured reason, or {@code null} for a plain decision
 */
public record OpaDecision(boolean allow, DenyReason denyReason) {

    private static final OpaDecision PERMIT = new OpaDecision(true, null);
    private static final OpaDecision DENY = new OpaDecision(false, null);

    /**
     * A plain allow.
     *
     * <p>Named {@code permit} rather than {@code allow} only because a record's component accessor
     * ({@link #allow()}) already owns that name.
     */
    public static OpaDecision permit() {
        return PERMIT;
    }

    /**
     * A plain deny with <strong>no</strong> reason — the value every fail-closed path returns.
     *
     * <p>Named so the fail-closed sites read as a deliberate choice: an outage, a breaker-open, a
     * malformed response and an exhausted retry all deny <em>without</em> a fabricated reason.
     */
    public static OpaDecision deny() {
        return DENY;
    }

    /** A plain decision (no reason) — the shape the {@code decide} default method produces. */
    public static OpaDecision of(boolean allow) {
        return allow ? PERMIT : DENY;
    }

    /** Whether this decision carries a reason complete enough to act on. */
    public boolean hasCompleteReason() {
        return denyReason != null && denyReason.isComplete();
    }
}
