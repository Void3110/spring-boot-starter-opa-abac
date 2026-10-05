package dev.dmitriikonovalov.opaabac.security.resilience;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The backend-agnostic resilience seam every cross-service HTTP edge calls through (Slice B3, ADR 0017 §7):
 * <em>execute a body with retry + circuit-breaker</em>, classifying both thrown exceptions and returned
 * results as retryable or terminal. One {@code CallGuard} instance per edge (OPA, resolve, tag), each built
 * from that edge's {@link ResilienceConfig} and owning that edge's breaker.
 *
 * <h2>Why no Resilience4j in the signature</h2>
 * B3 ships only the {@link Resilience4jCallGuard} impl, but a Spring Framework 7 / Spring Boot 4 native
 * backend ({@code @Retryable}, {@code RetryTemplate}, {@code @ConcurrencyLimit}, zero external deps) is a
 * known future. Keeping every Resilience4j type out of this interface makes that migration a
 * <strong>one-impl swap</strong> (a {@code NativeCallGuard} behind the same seam) instead of a three-edge
 * rewrite. No caller — the OPA decorator, the app-side resolve/tag wrappers — references a backend type.
 *
 * <h2>Retry-on-result, not just retry-on-exception</h2>
 * The edges classify an HTTP failure <em>after</em> a successful {@code send()}: a 5xx comes back as a
 * normal response object, a transport error comes back as an exception. So a {@code CallGuard} retries on
 * <strong>either</strong> a {@code retryableError} (the thrown transport/timeout failure) or a
 * {@code retryableResult} (a returned value the caller deems a transient failure — e.g. a 5xx response).
 * Both predicates come from the caller; the shared classification lives in {@link RetryableClassification}.
 * Whatever the body produced on the final attempt — value or the last thrown cause — is returned/re-thrown
 * unchanged, so the caller's fail-closed mapping sees the original outcome (never a backend wrapper).
 *
 * <h2>What the breaker counts</h2>
 * A returned value never counts. A thrown failure counts unless the caller narrows it with the four-argument
 * {@link #call(Supplier, Predicate, Predicate, Predicate) call}'s {@code recordableError}: the OPA decorator
 * counts only the faults it retries, because a fail-fast answer (an undefined decision, a malformed body) is
 * often one type's or one method's defect, and one breaker serves every type and method (ADR 0037 §4).
 *
 * <h2>Fail-closed is the caller's job, not the guard's</h2>
 * The {@code CallGuard} is a latency/load optimization over the fail-closed path, never a decision input
 * (ADR 0017 §5). On a breaker-open short-circuit it throws {@link CallNotPermittedException} <em>without</em>
 * invoking the body; the caller maps that — like an exhausted retry — to its own fail-closed outcome (the OPA
 * decorator to a {@code PolicyEngineException} of kind {@code CIRCUIT_OPEN}, the resolve/tag wrappers to
 * their exceptions). The guard never synthesizes a domain result. Every breaker state yields an outcome
 * already reachable without the breaker; open is strictly <em>more</em> fail-closed, never less.
 *
 * <h2>Deterministic timing</h2>
 * The impl takes an injectable clock/scheduler so all retry/backoff/breaker behavior can be driven in
 * tests at virtual time — zero {@code Thread.sleep}, zero wall-clock assertions (ADR 0017 §Proof). That is
 * a design constraint on the seam, not a test afterthought.
 */
public interface CallGuard {

    /**
     * Execute {@code body} under this edge's retry + breaker budget.
     *
     * @param body            the side-effect-free HTTP exchange to run (read-only — a retry, including
     *                        after a read-timeout, must not double-execute; ADR 0017 §3)
     * @param retryableError  classifies a thrown exception as a transient (retry) vs a permanent failure;
     *                        a non-retryable throw is re-thrown immediately, no retry
     * @param retryableResult classifies a <em>returned</em> value as a transient failure that should be
     *                        retried (e.g. a 5xx response wrapper); a non-retryable value is returned as-is
     * @param <T>             the body's result type
     * @return the body's result from the first non-retryable (or final) attempt
     * @throws CallNotPermittedException if the breaker is open (the body is not invoked)
     * @throws RuntimeException          the last cause, unchanged, if the budget is exhausted on a thrown
     *                                   failure
     */
    <T> T call(Supplier<T> body, Predicate<Throwable> retryableError, Predicate<T> retryableResult);

    /**
     * {@link #call(Supplier, Predicate, Predicate) call}, with the caller choosing which thrown failures the
     * breaker counts. A thrown failure {@code recordableError} rejects is still retried or re-thrown as
     * {@code retryableError} says, but it neither counts toward opening the breaker nor resets its window.
     *
     * <p>The default ignores {@code recordableError} and counts every thrown failure, as the three-argument
     * form does; {@link Resilience4jCallGuard} honours it.
     *
     * @param recordableError classifies a thrown exception as one the breaker counts
     * @since 1.4.0
     */
    default <T> T call(Supplier<T> body, Predicate<Throwable> retryableError, Predicate<T> retryableResult,
            Predicate<Throwable> recordableError) {
        return call(body, retryableError, retryableResult);
    }
}
