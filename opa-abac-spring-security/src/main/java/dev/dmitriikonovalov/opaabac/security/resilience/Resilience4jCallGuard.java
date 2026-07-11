package dev.dmitriikonovalov.opaabac.security.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Resilience4j-backed {@link CallGuard} — the only B3 impl (ADR 0017 §7); a Spring-Framework-7 /
 * Spring-Boot-4 native backend is a later one-impl swap behind the same seam. One instance per edge,
 * holding that edge's {@link CircuitBreaker} and retry budget.
 *
 * <h2>What it does on each {@code call}</h2>
 * <ol>
 *   <li>If the edge is {@link ResilienceConfig#enabled() disabled}, runs the body once, unguarded — no
 *       retry, no breaker, byte-identical to pre-B3.</li>
 *   <li>Otherwise asks the breaker for permission; if the breaker is open it throws
 *       {@link CallNotPermittedException} <em>without</em> invoking the body (the caller fails closed).</li>
 *   <li>Runs the body. A thrown {@code retryableError}, or a returned {@code retryableResult}, is a
 *       transient failure: it is recorded on the breaker and retried after exponential backoff with full
 *       jitter, up to {@link ResilienceConfig#maxRetries()} times and never past
 *       {@link ResilienceConfig#ceiling()} of total elapsed time.</li>
 *   <li>On the final attempt the body's outcome is returned/re-thrown <strong>unchanged</strong> — the last
 *       value, or the last thrown cause — so the caller's fail-closed mapping sees the original outcome,
 *       not a backend wrapper.</li>
 * </ol>
 *
 * <h2>Deterministic timing (the injected seams)</h2>
 * The breaker is built with an injected {@link Clock} — public API since R4j 2.3.0 via
 * {@code CircuitBreakerConfig.Builder#clock(Clock)} — so its open-duration window advances at virtual time;
 * backoff waiting goes through an injected {@code sleeper} ({@link LongConsumer} of millis) a test stubs to
 * a no-op (recording the requested wait) so retry/backoff/breaker tests never call {@code Thread.sleep} and
 * never assert against the wall clock (ADR 0017 §Proof). The {@link #Resilience4jCallGuard(String,
 * ResilienceConfig)} convenience constructor wires the system clock and a real sleeper for production.
 */
public final class Resilience4jCallGuard implements CallGuard {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jCallGuard.class);

    private final String name;
    private final ResilienceConfig config;
    private final CircuitBreaker breaker;
    private final IntervalFunction backoff;
    private final Clock clock;
    private final LongConsumer sleeper;

    /** Production constructor: system clock, a real {@code Thread.sleep} backoff waiter. */
    public Resilience4jCallGuard(String name, ResilienceConfig config) {
        this(name, config, Clock.systemUTC(), Resilience4jCallGuard::sleepMillis);
    }

    /**
     * Full constructor — a test supplies a virtual {@link Clock} (drives the breaker's open-duration
     * window) and a no-op {@code sleeper} (so backoff waiting is instant yet observable).
     *
     * @param name    the edge name (OPA / resolve / tag) — labels the breaker and the logs
     * @param config  this edge's budget
     * @param clock   the clock the breaker reads (virtual in tests)
     * @param sleeper consumes a backoff interval in millis; production sleeps, tests record and return
     */
    public Resilience4jCallGuard(String name, ResilienceConfig config, Clock clock, LongConsumer sleeper) {
        this.name = Objects.requireNonNull(name, "name");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.backoff = IntervalFunction.ofExponentialRandomBackoff(config.backoff());
        this.breaker = buildBreaker(name, config, clock);
    }

    private static CircuitBreaker buildBreaker(String name, ResilienceConfig config, Clock clock) {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                // A count-based window the size of the failure threshold: N consecutive failures open it.
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.failureThreshold())
                .minimumNumberOfCalls(config.failureThreshold())
                .failureRateThreshold(100.0f) // the whole window must fail — a deterministic threshold
                .waitDurationInOpenState(config.openDuration())
                .permittedNumberOfCallsInHalfOpenState(config.halfOpenProbes())
                .automaticTransitionFromOpenToHalfOpenEnabled(false) // the next call probes; no timer thread
                // The Clock seam is public API since R4j 2.3.0 (2.4.0 removed the internal constructor
                // this guard previously used): the open-duration window advances at *virtual* time in
                // tests — driving the clock forward then calling through the guard moves open → half-open
                // without sleeping (ADR 0017 §7, "eliminated" addendum).
                .clock(clock)
                .build();
        return CircuitBreaker.of(name, cbConfig);
    }

    @Override
    public <T> T call(Supplier<T> body, Predicate<Throwable> retryableError, Predicate<T> retryableResult) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(retryableError, "retryableError");
        Objects.requireNonNull(retryableResult, "retryableResult");

        // Kill-switch off (or no budget): a single, unguarded attempt — byte-identical to pre-B3.
        if (!config.enabled()) {
            return body.get();
        }

        long deadlineMillis = clock.millis() + config.ceiling().toMillis();
        int attempt = 0;
        RuntimeException lastError = null;
        while (true) {
            attempt++;
            // Breaker gate: open ⇒ short-circuit without invoking the body (the caller fails closed).
            if (!breaker.tryAcquirePermission()) {
                throw new CallNotPermittedException(
                        "circuit breaker '" + name + "' is open", lastError);
            }
            try {
                T result = body.get();
                if (retryableResult.test(result)) {
                    // A returned retryable sentinel: RETRY it (a transient blip may recover), but do NOT
                    // record it on the breaker. The body produced a *value*, not a thrown fault — and on the
                    // OPA edge that sentinel (allow=false / compile fromError / all-false) is indistinguishable
                    // from a genuine policy DENY. Feeding a decision into the breaker would let sustained
                    // legitimate denials self-open it and then force-deny otherwise-allowable requests — a
                    // self-inflicted availability regression that ADR 0017 §5 forbids ("never a decision
                    // input"). Only a *thrown* retryableError (an unambiguous fault) drives the breaker below.
                    if (canRetry(attempt, deadlineMillis)) {
                        backoffBeforeRetry(attempt);
                        continue;
                    }
                    return result; // budget exhausted — return the last value unchanged (the caller maps it)
                }
                breaker.onSuccess(0L, TimeUnit.NANOSECONDS);
                return result;
            } catch (RuntimeException e) {
                // A thrown failure IS an unambiguous fault (transport error / a 5xx surfaced as an
                // exception by an edge wrapper), so it records on the breaker — both retryable and not.
                recordFailure(e);
                if (!retryableError.test(e)) {
                    // Permanent failure (e.g. a 4xx surfaced as an exception): re-throw at once, no retry.
                    throw e;
                }
                lastError = e;
                if (canRetry(attempt, deadlineMillis)) {
                    backoffBeforeRetry(attempt);
                    continue;
                }
                throw e; // budget exhausted — re-throw the last cause unchanged (the caller maps it)
            }
        }
    }

    // This guard's breaker is FAILURE-COUNT based, not latency based: it opens after `failureThreshold`
    // consecutive *thrown faults* (failureRateThreshold=100% over a count window), never on a returned
    // sentinel and never on a slow-but-successful call (ADR 0017 §5 — the breaker is a load/availability
    // optimization, never a decision input). The recorded call duration is irrelevant to the open/close
    // decision and is reported as 0.
    private void recordFailure(RuntimeException failure) {
        breaker.onError(0L, TimeUnit.NANOSECONDS, failure);
    }

    private boolean canRetry(int attempt, long deadlineMillis) {
        if (attempt >= config.maxAttempts()) {
            return false;
        }
        return clock.millis() < deadlineMillis;
    }

    private void backoffBeforeRetry(int attempt) {
        // IntervalFunction is 1-based on the number of the *completed* attempt.
        long waitMillis = backoff.apply(attempt);
        long ceilingMillis = config.ceiling().toMillis();
        if (waitMillis > ceilingMillis) {
            waitMillis = ceilingMillis; // never wait past the named ceiling
        }
        if (log.isTraceEnabled()) {
            log.trace("CallGuard '{}' retrying after {}ms (attempt {} of {})",
                    name, waitMillis, attempt, config.maxAttempts());
        }
        sleeper.accept(waitMillis);
    }

    private static void sleepMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CallNotPermittedException("interrupted during retry backoff", e);
        }
    }

    /** Visible to tests asserting breaker lifecycle without reaching into R4j directly. */
    public CircuitBreaker breaker() {
        return breaker;
    }
}
