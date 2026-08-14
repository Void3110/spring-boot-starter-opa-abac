package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.DenyReason;
import java.util.Objects;
import org.springframework.security.authorization.AuthorizationDecision;

/**
 * A <strong>denied</strong> {@link AuthorizationDecision} that additionally says <em>why</em>: the
 * subject would be allowed if they re-authenticated with a stronger, fresher factor (ADR 0030 §6–7).
 *
 * <p>It is a plain denied decision to everything that only asks {@code isGranted()} — Spring Security's
 * interceptor throws its usual {@code AuthorizationDeniedException} and carries this instance along as
 * the result, so an enforcement point that recognises the type can render an RFC 9470 challenge while
 * every other one renders the ordinary 403. <strong>Nothing here can turn a deny into an allow:</strong>
 * {@code granted} is fixed {@code false} by the constructor.
 *
 * <h2>The three log-only fields</h2>
 * Besides the reason, the decision carries the resource type + id and the governing root's id. They
 * exist for one purpose — the {@code STEP_UP_CHALLENGED} audit event is emitted at the enforcement
 * point, which no longer has the manager's resolved check to read them from. They are <strong>never
 * re-entered into a decision</strong>: nothing in this library reads them back, and an enforcement point
 * that did would be authorizing on a value the denied decision itself supplied.
 *
 * @see AbstractProblemAdvice
 */
public final class StepUpRequiredDecision extends AuthorizationDecision {

    private final transient DenyReason reason;
    private final String resourceType;
    private final String resourceId;
    private final String governingRootId;

    /**
     * @param reason          the structured reason (never {@code null}); only a
     *                        {@link DenyReason#isComplete() complete} one can produce a challenge
     * @param resourceType    the decided resource's type — log-only
     * @param resourceId      the decided resource's id, or {@code null} for a type-level check — log-only
     * @param governingRootId the governing root's id, or {@code null} when there is none — log-only
     */
    public StepUpRequiredDecision(
            DenyReason reason, String resourceType, String resourceId, String governingRootId) {
        super(false);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.governingRootId = governingRootId;
    }

    /** The structured reason; never {@code null}, but possibly incomplete. */
    public DenyReason reason() {
        return reason;
    }

    /** The decided resource's type — log-only. */
    public String resourceType() {
        return resourceType;
    }

    /** The decided resource's id, or {@code null} for a type-level check — log-only. */
    public String resourceId() {
        return resourceId;
    }

    /** The governing root's id, or {@code null} — log-only. */
    public String governingRootId() {
        return governingRootId;
    }

    @Override
    public String toString() {
        return "StepUpRequiredDecision[granted=false, requiredAcr=" + reason.requiredAcr()
                + ", maxAge=" + reason.maxAge() + "]";
    }
}
