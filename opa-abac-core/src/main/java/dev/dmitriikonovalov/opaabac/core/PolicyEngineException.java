package dev.dmitriikonovalov.opaabac.core;

import java.util.OptionalInt;

/**
 * The policy engine could not produce a decision (ADR 0037 §3–§4) — thrown by {@link OpaClient}
 * implementations instead of a fabricated deny.
 *
 * <p>The {@link Kind} says why, so a caller can decide what to do without parsing a message: the resilience
 * layer retries only the transient kinds, a log line names the kind, and an adopter can tell a policy
 * deployment that never loaded ({@link Kind#UNDEFINED_DECISION}) or a policy that errors on this input
 * ({@link Kind#EVALUATION_ERROR}) from a sidecar that is down ({@link Kind#TRANSPORT}).
 *
 * <p>Instances are built through the static factories, one per kind, so the {@link #httpStatus()} invariant
 * (present exactly for {@link Kind#HTTP_STATUS}) cannot be broken.
 */
public final class PolicyEngineException extends DecisionIndeterminateException {

    /** Why no decision could be obtained. */
    public enum Kind {
        /** The connection failed or broke: refused, reset, closed early — any transport {@code IOException}. */
        TRANSPORT,
        /** The connect or request timeout elapsed. */
        TIMEOUT,
        /** The engine answered with a status other than {@code 200}; {@link #httpStatus()} carries it. */
        HTTP_STATUS,
        /** The calling thread was interrupted; the interrupt flag has been restored. */
        INTERRUPTED,
        /** A {@code 200} whose body is not a decision: unparseable, a non-boolean decision, a wrong-shaped result. */
        MALFORMED_RESPONSE,
        /**
         * A {@code 200} with no {@code result} at all: the policy document is not loaded (a bundle not yet
         * activated, a wrong path) — or, on a bulk call, the package defines no {@code bulk} rule.
         */
        UNDEFINED_DECISION,
        /**
         * The engine answered, but the policy itself failed on this input — OPA's policy-local evaluation errors
         * ({@code eval_conflict_error}: a complete rule producing two outputs; {@code eval_type_error};
         * {@code eval_with_merge_error}), which its data API reports as an HTTP {@code 500}. Treated as
         * deterministic for the input: not retried, and not counted on a circuit breaker. OPA's operational
         * {@code eval_*} codes (cancellation, internal, built-in and {@code http.send} failures) stay
         * {@link #HTTP_STATUS}.
         *
         * @since 1.4.0
         */
        EVALUATION_ERROR,
        /** The resilience layer's circuit breaker is open; the engine was not called. */
        CIRCUIT_OPEN
    }

    private static final int NO_STATUS = -1;

    private final Kind kind;
    private final int httpStatus;

    private PolicyEngineException(Kind kind, int httpStatus, String detail, Throwable cause) {
        super(kind + ": " + detail, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    /** A transport failure (connection refused, reset, closed early). */
    public static PolicyEngineException transport(String detail, Throwable cause) {
        return new PolicyEngineException(Kind.TRANSPORT, NO_STATUS, detail, cause);
    }

    /** The connect or request timeout elapsed. */
    public static PolicyEngineException timeout(String detail, Throwable cause) {
        return new PolicyEngineException(Kind.TIMEOUT, NO_STATUS, detail, cause);
    }

    /** The engine answered {@code status}, which is not {@code 200}. */
    public static PolicyEngineException httpStatus(int status, String detail) {
        return new PolicyEngineException(Kind.HTTP_STATUS, status, detail, null);
    }

    /** The calling thread was interrupted. The caller restores the interrupt flag before throwing this. */
    public static PolicyEngineException interrupted(String detail, Throwable cause) {
        return new PolicyEngineException(Kind.INTERRUPTED, NO_STATUS, detail, cause);
    }

    /** A {@code 200} whose body is not a decision; {@code cause} may be {@code null}. */
    public static PolicyEngineException malformedResponse(String detail, Throwable cause) {
        return new PolicyEngineException(Kind.MALFORMED_RESPONSE, NO_STATUS, detail, cause);
    }

    /** A {@code 200} with no {@code result}: the policy document (or its bulk rule) is not there. */
    public static PolicyEngineException undefinedDecision(String detail) {
        return new PolicyEngineException(Kind.UNDEFINED_DECISION, NO_STATUS, detail, null);
    }

    /** The policy itself failed on this input (OPA's policy-local {@code eval_*} errors). */
    public static PolicyEngineException evaluationError(String detail) {
        return new PolicyEngineException(Kind.EVALUATION_ERROR, NO_STATUS, detail, null);
    }

    /** The circuit breaker is open; {@code cause} may be {@code null}. */
    public static PolicyEngineException circuitOpen(String detail, Throwable cause) {
        return new PolicyEngineException(Kind.CIRCUIT_OPEN, NO_STATUS, detail, cause);
    }

    /** Why no decision could be obtained; never {@code null}. */
    public Kind kind() {
        return kind;
    }

    /** The engine's answer status — present exactly when {@link #kind()} is {@link Kind#HTTP_STATUS}. */
    public OptionalInt httpStatus() {
        return httpStatus == NO_STATUS ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }
}
