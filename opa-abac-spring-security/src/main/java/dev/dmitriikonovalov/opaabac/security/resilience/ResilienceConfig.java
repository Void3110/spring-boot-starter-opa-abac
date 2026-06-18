package dev.dmitriikonovalov.opaabac.security.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * The per-edge resilience budget — an immutable, framework-agnostic value carrying everything a
 * {@link CallGuard} needs to build its retry and circuit-breaker behavior. One instance per cross-service
 * HTTP edge (OPA, resolve, tag), so each edge gets its own asymmetric budget and its own breaker.
 *
 * <h2>Asymmetric per-edge budgets — "uniform" is the shape, not the numbers</h2>
 * The three edges share the <em>same config shape</em> and the <em>same fail-closed contract</em>, but not
 * the same numbers (Slice B3, ADR 0017 §4). The OPA gate runs on <em>every</em> request against a local
 * sidecar, so its failure looks like a restart blip and it retries little (default 1 retry / ~2–3s ceiling)
 * — over-retrying the hot path would only lengthen the deny wall B3 exists to soften. The cross-service
 * resolve/tag hops see real transient weather and run less often, so they retry more (default 2 / ~6s).
 *
 * <h2>{@code enabled} — the per-edge kill-switch</h2>
 * When {@code enabled} is {@code false} the {@link CallGuard} performs a single, unguarded attempt — no
 * retry, no breaker — so behavior is byte-identical to pre-B3 (ADR 0017 §9). The switch governs
 * retry/breaker only; the caller's fail-closed mapping is unaffected in every config state.
 *
 * @param enabled          {@code false} ⇒ a single unguarded attempt (the pre-B3 baseline)
 * @param maxRetries       retries <em>after</em> the first attempt (so total attempts = {@code maxRetries + 1})
 * @param backoff          the base exponential-backoff interval (full jitter is applied on top)
 * @param ceiling          the named, configurable upper bound on total time spent across all attempts
 * @param failureThreshold consecutive/windowed failures that open the breaker
 * @param openDuration     how long the breaker stays open before a half-open probe
 * @param halfOpenProbes   permitted probe calls in the half-open state
 */
public record ResilienceConfig(
        boolean enabled,
        int maxRetries,
        Duration backoff,
        Duration ceiling,
        int failureThreshold,
        Duration openDuration,
        int halfOpenProbes) {

    public ResilienceConfig {
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(ceiling, "ceiling");
        Objects.requireNonNull(openDuration, "openDuration");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, was " + maxRetries);
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1, was " + failureThreshold);
        }
        if (halfOpenProbes < 1) {
            throw new IllegalArgumentException("halfOpenProbes must be >= 1, was " + halfOpenProbes);
        }
    }

    /** Total attempts the budget permits: the first try plus {@link #maxRetries()} retries. */
    public int maxAttempts() {
        return maxRetries + 1;
    }
}
