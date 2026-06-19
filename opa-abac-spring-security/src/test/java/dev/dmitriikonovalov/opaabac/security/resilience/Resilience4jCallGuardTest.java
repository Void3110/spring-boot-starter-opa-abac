package dev.dmitriikonovalov.opaabac.security.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * U1/U2/U6/U7 — the {@link Resilience4jCallGuard} under <strong>virtual time</strong> (ADR 0017 §Proof):
 * a {@link MutableClock} the backoff {@code sleeper} advances, so every retry/backoff/breaker assertion is
 * deterministic with <em>zero</em> {@code Thread.sleep} and <em>zero</em> wall-clock reads.
 */
class Resilience4jCallGuardTest {

    private static final Predicate<Throwable> RETRY_IO = RetryableClassification.retryableError();

    /** A result predicate that never asks for a retry — typed per call so {@code T} unifies cleanly. */
    private static <T> Predicate<T> neverRetryResult() {
        return r -> false;
    }

    /** A clock + a sleeper that advances it: backoff "waits" pass virtual time without sleeping. */
    private final MutableClock clock = MutableClock.startingAtEpoch();
    private long sleptTotalMillis = 0;
    private final LongConsumer advancingSleeper = millis -> {
        sleptTotalMillis += millis;
        clock.advanceMillis(millis);
    };

    private Resilience4jCallGuard guard(String name, ResilienceConfig config) {
        return new Resilience4jCallGuard(name, config, clock, advancingSleeper);
    }

    // --- config helpers ----------------------------------------------------------------

    /** OPA-edge-like budget: 1 retry, 50ms backoff, 3s ceiling, breaker opens after 3 failures. */
    private static ResilienceConfig opaBudget() {
        return new ResilienceConfig(true, 1, Duration.ofMillis(50), Duration.ofSeconds(3),
                3, Duration.ofSeconds(5), 1);
    }

    /** resolve/tag-edge-like budget: 2 retries, 50ms backoff, 6s ceiling. */
    private static ResilienceConfig resolveBudget() {
        return new ResilienceConfig(true, 2, Duration.ofMillis(50), Duration.ofSeconds(6),
                3, Duration.ofSeconds(5), 1);
    }

    // --- U1: attempt counts per classification -----------------------------------------

    @Test // a retryable exception is retried up to maxRetries → total attempts = maxRetries + 1
    void retryableException_exhaustsBudget() {
        AtomicInteger attempts = new AtomicInteger();
        ResilienceConfig cfg = resolveBudget(); // 2 retries → 3 attempts

        assertThatThrownBy(() -> guard("resolve", cfg).call(() -> {
            attempts.incrementAndGet();
            throw new UncheckedIOException(new IOException("connection reset"));
        }, RETRY_IO, neverRetryResult())).isInstanceOf(UncheckedIOException.class);

        assertThat(attempts).hasValue(cfg.maxAttempts()).hasValue(3);
    }

    @Test // a non-retryable exception (4xx-as-exception, parse failure) → exactly ONE attempt, no retry
    void nonRetryableException_failsFast() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> guard("resolve", resolveBudget()).call(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("malformed body"); // not an IOException → not retryable
        }, RETRY_IO, neverRetryResult())).isInstanceOf(IllegalStateException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test // a retryable result (e.g. a 5xx response) is retried then returned unchanged on exhaustion
    void retryableResult_exhaustsBudgetThenReturnsLastValue() {
        AtomicInteger attempts = new AtomicInteger();
        Predicate<Integer> fiveXx = status -> RetryableClassification.retryableStatus(status);

        Integer result = guard("opa", resolveBudget()).call(() -> {
            attempts.incrementAndGet();
            return 503; // always a transient status → retried, never recovers
        }, RETRY_IO, fiveXx);

        assertThat(result).isEqualTo(503); // the last value, unchanged — the caller maps it to fail-closed
        assertThat(attempts).hasValue(3); // 2 retries + 1
    }

    @Test // a body that recovers within budget returns the recovered value
    void recoversWithinBudget_returnsSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        Predicate<Integer> fiveXx = status -> RetryableClassification.retryableStatus(status);

        Integer result = guard("resolve", resolveBudget()).call(() -> {
            int n = attempts.incrementAndGet();
            return n < 2 ? 503 : 200; // first attempt transient, second recovers
        }, RETRY_IO, fiveXx);

        assertThat(result).isEqualTo(200);
        assertThat(attempts).hasValue(2);
    }

    @Test // the happy path: one attempt, no backoff
    void success_firstAttempt_noBackoff() {
        AtomicInteger attempts = new AtomicInteger();

        String result = guard("opa", opaBudget()).call(() -> {
            attempts.incrementAndGet();
            return "ok";
        }, RETRY_IO, neverRetryResult());

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(1);
        assertThat(sleptTotalMillis).isZero();
    }

    @Test // kill-switch off → a single unguarded attempt, byte-identical to pre-B3 (no retry on a failure)
    void disabled_singleUnguardedAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        ResilienceConfig off = new ResilienceConfig(false, 2, Duration.ofMillis(50), Duration.ofSeconds(6),
                3, Duration.ofSeconds(5), 1);

        assertThatThrownBy(() -> guard("resolve", off).call(() -> {
            attempts.incrementAndGet();
            throw new UncheckedIOException(new IOException("reset")); // retryable class, switch off → no retry
        }, RETRY_IO, neverRetryResult())).isInstanceOf(UncheckedIOException.class);

        assertThat(attempts).hasValue(1);
    }

    // --- U2: exhaustion re-throws the last cause UNCHANGED ------------------------------

    @Test // the exact last exception instance is re-thrown — no R4j wrapper around it
    void exhaustion_reThrowsLastCauseUnchanged() {
        UncheckedIOException[] thrown = new UncheckedIOException[3];
        AtomicInteger i = new AtomicInteger();

        Throwable caught = catchThrowable(() -> guard("resolve", resolveBudget()).call(() -> {
            UncheckedIOException e = new UncheckedIOException(new IOException("attempt " + i.get()));
            thrown[i.getAndIncrement()] = e;
            throw e;
        }, RETRY_IO, neverRetryResult()));

        // the caller sees the ORIGINAL exception instance (its fail-closed mapping depends on it), not a
        // RetryException / backend wrapper — and specifically the LAST attempt's instance.
        assertThat(caught).isInstanceOf(UncheckedIOException.class).isSameAs(thrown[2]);
        assertThat(caught).hasMessage("java.io.IOException: attempt 2");
    }

    // --- U6: latency bound under virtual time ------------------------------------------

    @Test // total virtual time spent in backoff stays within the ceiling; attempts ≤ maxRetries+1
    void latencyBound_totalWithinCeiling() {
        ResilienceConfig cfg = resolveBudget(); // 2 retries, 50ms base, 6s ceiling
        long start = clock.millis();
        AtomicInteger attempts = new AtomicInteger();

        catchThrowable(() -> guard("resolve", cfg).call(() -> {
            attempts.incrementAndGet();
            throw new UncheckedIOException(new IOException("down"));
        }, RETRY_IO, neverRetryResult()));

        long elapsed = clock.millis() - start;
        assertThat(attempts.get()).isLessThanOrEqualTo(cfg.maxAttempts());
        assertThat(elapsed).as("total backoff within ceiling").isLessThanOrEqualTo(cfg.ceiling().toMillis());
        assertThat(sleptTotalMillis).isEqualTo(elapsed);
    }

    @Test // a tiny ceiling cuts retries short: the loop stops as soon as the deadline passes
    void latencyBound_tightCeilingStopsEarly() {
        // 5 retries permitted, but a 10ms ceiling: backoff (≥ ~50ms base) blows the budget after attempt 1.
        ResilienceConfig tight = new ResilienceConfig(true, 5, Duration.ofMillis(50), Duration.ofMillis(10),
                3, Duration.ofSeconds(5), 1);
        AtomicInteger attempts = new AtomicInteger();

        catchThrowable(() -> guard("opa", tight).call(() -> {
            attempts.incrementAndGet();
            throw new UncheckedIOException(new IOException("down"));
        }, RETRY_IO, neverRetryResult()));

        // first attempt fails, one backoff (capped at the 10ms ceiling) advances past the deadline, so the
        // second attempt's canRetry sees now >= deadline → at most 2 attempts, never the full 6.
        assertThat(attempts.get()).isLessThanOrEqualTo(2);
    }

    // --- U7: breaker lifecycle under virtual time --------------------------------------

    @Test // N consecutive failures open the breaker; once open it short-circuits without calling the body
    void breaker_opensAfterThreshold_thenShortCircuits() {
        // 0 retries so each call() is one breaker observation; opens after 3 failures.
        ResilienceConfig cfg = new ResilienceConfig(true, 0, Duration.ofMillis(50), Duration.ofSeconds(3),
                3, Duration.ofSeconds(5), 1);
        Resilience4jCallGuard g = guard("opa", cfg);
        AtomicInteger bodyCalls = new AtomicInteger();

        // three failing calls fill the count-based window and open the breaker
        for (int i = 0; i < 3; i++) {
            catchThrowable(() -> g.call(() -> {
                bodyCalls.incrementAndGet();
                throw new UncheckedIOException(new IOException("down"));
            }, RETRY_IO, neverRetryResult()));
        }
        assertThat(g.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // the next call short-circuits: CallNotPermittedException, body NOT invoked
        int before = bodyCalls.get();
        assertThatThrownBy(() -> g.call(() -> {
            bodyCalls.incrementAndGet();
            return "should-not-run";
        }, RETRY_IO, neverRetryResult())).isInstanceOf(CallNotPermittedException.class);
        assertThat(bodyCalls.get()).as("open breaker did not invoke the body").isEqualTo(before);
    }

    @Test // open → (openDuration elapses, virtual) → half-open probe success → closed
    void breaker_halfOpenProbeSuccess_closesIt() {
        ResilienceConfig cfg = new ResilienceConfig(true, 0, Duration.ofMillis(50), Duration.ofSeconds(3),
                3, Duration.ofSeconds(5), 1);
        Resilience4jCallGuard g = guard("opa", cfg);

        for (int i = 0; i < 3; i++) {
            catchThrowable(() -> g.call(() -> {
                throw new UncheckedIOException(new IOException("down"));
            }, RETRY_IO, neverRetryResult()));
        }
        assertThat(g.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // advance virtual time past the open duration → the next permission check transitions to half-open
        clock.advance(Duration.ofSeconds(6));

        // a successful probe closes the breaker
        String result = g.call(() -> "recovered", RETRY_IO, neverRetryResult());
        assertThat(result).isEqualTo("recovered");
        assertThat(g.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static Throwable catchThrowable(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
