package dev.dmitriikonovalov.opaabac.security.resilience;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A resilient {@link OpaClient} decorator (Slice B3, ADR 0017 §1/§2) — it wraps a plain delegate
 * {@code OpaClient} (the {@link dev.dmitriikonovalov.opaabac.core.HttpOpaClient}) and runs each of the three
 * decision calls through the OPA-edge {@link CallGuard}, so a <em>transient</em> OPA blip recovering within
 * budget no longer surfaces as a denial. Core is untouched — {@code OpaClient} is already an interface, so
 * the resilience lives one level up in the Spring layer (no pluggable transport in core; route "D" rejected).
 *
 * <h2>Why retry on the fail-closed sentinel</h2>
 * The plain {@code HttpOpaClient} <em>swallows</em> every transport/parse failure into its fail-closed value
 * ({@code allow}→{@code false}, {@code compile}→{@link PartialResult#error()}, {@code allowAll}→all-false) —
 * it never throws. So the decorator cannot retry on an exception; it retries on the <strong>returned
 * sentinel</strong>:
 * <ul>
 *   <li>{@code compile} retries iff {@link PartialResult#fromError()} — the <em>exact</em> failure signal,
 *       cleanly distinct from a real {@code denyAll()} ({@code fromError==false}, a genuine "no rows").</li>
 *   <li>{@code allow} retries on {@code false}. A genuine policy deny <em>also</em> retries — a single
 *       boolean has no way to tell the sentinel from a real deny — but the OPA gate is a local sidecar at
 *       a 1-retry / ~50ms budget, an OPA decision is deterministic (a real deny stays {@code false}, never
 *       widens), so the cost is one extra fast hop on a deny while a transient blip recovers the real answer.</li>
 *   <li>{@code allowAll} retries iff the block is <strong>all-{@code false}</strong> — the exact value the
 *       delegate pads on a transport/parse failure — never on a <em>mixed</em> block: per-element verdicts
 *       can only come from a real {@code 200}, so a mixed block is a real answer and retrying it would put
 *       a retry + backoff on every honest affordance page that contains a single denied verb (measured in
 *       Slice 7.3: it doubled the bulk-eval load and multiplied steady enrichment latency ~8×). A genuine
 *       all-false page (rare — the enrichment advice omits those blocks anyway) pays one extra fast hop,
 *       exactly like a genuine {@code allow} deny. Fail-closed is preserved in every case — resilience
 *       makes outages rarer, never wider.</li>
 * </ul>
 *
 * <h2>The decorator OWNS the fail-closed values (breaker-open + exhausted-retry)</h2>
 * On {@link CallNotPermittedException} (breaker open — the delegate is <em>not</em> invoked) the decorator
 * synthesizes the delegate's fail-closed value by hand: {@code false} / {@link PartialResult#error()} /
 * {@code n}×{@code false}. It is <strong>never</strong> {@code denyAll()} (which has {@code fromError==false}
 * — a 5.5-B hierarchy {@code subtreeSpec} widening could survive next to it) and <strong>never</strong>
 * {@code allowAll()} (the catastrophe value). A contract test pins decorator-value == delegate-value in
 * every state. The decorator is a real {@code OpaClient} — it implements all three methods, fail-closed, by
 * hand (no {@code default} fail-open).
 *
 * <p>On exhausted retry the guard returns the delegate's last (still fail-closed) value unchanged, so the
 * decorator returns it as-is — identical to the breaker-open synthesis by construction.
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
        try {
            // Retry while the delegate reports the fail-closed sentinel (false). A genuine deny retries too
            // (1 fast sidecar hop, deterministic → still false); a transient blip recovers the real answer.
            return guard.call(() -> delegate.allow(context), retryableError, denied -> denied == Boolean.FALSE);
        } catch (CallNotPermittedException e) {
            // Breaker open: the delegate was never called. Synthesize the same fail-closed value by hand.
            log.warn("OPA allow fail-closed: circuit breaker open (denying)");
            return false;
        }
    }

    @Override
    public PartialResult compile(AbacContext context) {
        try {
            // The clean case: fromError is the exact failure flag, distinct from a real denyAll()/conditional.
            return guard.call(() -> delegate.compile(context), retryableError, PartialResult::fromError);
        } catch (CallNotPermittedException e) {
            // Breaker open: error() (fromError=true), NEVER denyAll()/allowAll() — a denyAll() (fromError
            // false) would let a 5.5-B hierarchy subtreeSpec widening survive an OPA outage (the landmine).
            log.warn("OPA compile fail-closed: circuit breaker open (deny-all, fromError)");
            return PartialResult.error();
        }
    }

    @Override
    public List<Boolean> allowAll(List<AbacContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of(); // mirror the delegate's no-HTTP-call short-circuit
        }
        int n = contexts.size();
        try {
            // Retry iff the block is the fail-closed sentinel: ALL-false (what the delegate pads on a
            // transport/parse failure) or null/short. A MIXED block is a real 200 answer — never retried
            // (retrying it would tax every honest page carrying one denied verb; see the class javadoc).
            return guard.call(
                    () -> delegate.allowAll(contexts),
                    retryableError,
                    decisions -> decisions == null
                            || decisions.size() != n
                            || !decisions.contains(Boolean.TRUE));
        } catch (CallNotPermittedException e) {
            log.warn("OPA bulk fail-closed: circuit breaker open (denying all {})", n);
            return allFalse(n);
        }
    }

    private static List<Boolean> allFalse(int n) {
        Boolean[] values = new Boolean[n];
        java.util.Arrays.fill(values, Boolean.FALSE);
        return List.of(values);
    }
}
