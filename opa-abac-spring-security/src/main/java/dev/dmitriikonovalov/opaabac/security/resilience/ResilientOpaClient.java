package dev.dmitriikonovalov.opaabac.security.resilience;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaDecision;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.PolicyEngineException;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A resilient {@link OpaClient} decorator (Slice B3, ADR 0017 §1/§2, amended by ADR 0037 §4) — it wraps a
 * plain delegate {@code OpaClient} (the {@link dev.dmitriikonovalov.opaabac.core.HttpOpaClient}) and runs
 * each of the four decision calls through the OPA-edge {@link CallGuard}, so a <em>transient</em> OPA blip
 * recovering within budget no longer surfaces as an error. Core is untouched — {@code OpaClient} is already
 * an interface, so the resilience lives one level up in the Spring layer (no pluggable transport in core;
 * route "D" rejected).
 *
 * <h2>Retry faults, never decisions</h2>
 * The delegate throws {@link PolicyEngineException} when it could not obtain a decision, and returns only
 * what the policy answered. So the decorator retries a <em>thrown</em> fault whose kind is transient
 * ({@link RetryableClassification#isRetryableError(Throwable)}: transport, timeout, 5xx, 429) and
 * <strong>never a returned value</strong>: a deny, a reasoned deny, a mixed or an all-false bulk page are
 * real answers, called exactly once. (Before ADR 0037 the delegate swallowed failures into its deny
 * values, so the decorator had to retry those — every genuine deny paid an extra OPA hop plus backoff, and
 * the breaker could not count the failures it was hiding.)
 *
 * <h2>When it still fails</h2>
 * An exhausted retry rethrows the delegate's last {@code PolicyEngineException} unchanged. An open breaker
 * — the delegate is <em>not</em> invoked — throws {@code PolicyEngineException} of kind
 * {@link PolicyEngineException.Kind#CIRCUIT_OPEN}; an interrupt during the guard's backoff throws kind
 * {@link PolicyEngineException.Kind#INTERRUPTED} (the guard has already restored the flag). The decorator
 * never synthesizes a deny for a failure, so in every config and breaker state it agrees with the plain
 * delegate: both throw the family for a failure, both return the same value for a decision. It is a real
 * {@code OpaClient} — all four methods implemented by hand, no {@code default} inherited.
 *
 * <p>The guard records every thrown fault on the breaker, transient or not (the other edges rely on that),
 * so a sustained deterministic fault — a policy package that never loaded — opens it like an outage does.
 * Accepted (ADR 0037 §4): every call answers "could not decide" either way, and the real kind is in the
 * WARN log of the attempts that opened it.
 */
public final class ResilientOpaClient implements OpaClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientOpaClient.class);

    private final OpaClient delegate;
    private final CallGuard guard;
    private final Predicate<Throwable> retryableError;

    /**
     * @param delegate the plain {@code OpaClient} to wrap (the production {@code HttpOpaClient})
     * @param guard    the OPA-edge {@link CallGuard} (its budget + breaker)
     */
    public ResilientOpaClient(OpaClient delegate, CallGuard guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.retryableError = RetryableClassification.retryableError();
    }

    @Override
    public boolean allow(AbacContext context) {
        return guarded("allow", () -> delegate.allow(context));
    }

    /**
     * The single decision, keeping the delegate's structured {@link OpaDecision#denyReason()}.
     *
     * <h2>Why this override is mandatory, not optional</h2>
     * {@code OpaClient.decide} is a {@code default} that delegates to {@code allow}. Without this
     * override, this decorator would inherit that default and it would call <em>this class's own</em>
     * {@code allow} — which returns a bare boolean — so the delegate's reason would be silently
     * swallowed and every step-up deny in the system would degrade to a plain 403. Nothing would fail,
     * nothing would log; the feature would simply not exist behind the decorator. A test asserts the
     * call goes through the guard, so the override cannot be removed without a red build.
     *
     * <p>A {@code null} from a delegate breaking the never-null contract is answered with a reasonless
     * deny, as before — a contract violation is not an outage.
     */
    @Override
    public OpaDecision decide(AbacContext context) {
        OpaDecision decision = guarded("decide", () -> delegate.decide(context));
        return decision == null ? OpaDecision.deny() : decision;
    }

    @Override
    public PartialResult compile(AbacContext context) {
        return guarded("compile", () -> delegate.compile(context));
    }

    @Override
    public List<Boolean> allowAll(List<AbacContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of(); // mirror the delegate's no-HTTP-call short-circuit
        }
        return guarded("bulk", () -> delegate.allowAll(contexts));
    }

    /**
     * Run one decision call through the guard: thrown transient faults are retried within budget, values
     * are returned at once, and the guard's own short-circuit becomes a {@link PolicyEngineException}.
     */
    private <T> T guarded(String operation, Supplier<T> body) {
        try {
            return guard.call(body, retryableError, result -> false); // a returned value is a decision
        } catch (CallNotPermittedException e) {
            if (e.getCause() instanceof InterruptedException) {
                log.warn("OPA {} indeterminate (fail-closed): interrupted during retry backoff", operation);
                throw PolicyEngineException.interrupted(operation + " interrupted during retry backoff", e);
            }
            log.warn("OPA {} indeterminate (fail-closed): circuit breaker open", operation);
            throw PolicyEngineException.circuitOpen(operation + ": circuit breaker open", e);
        }
    }
}
