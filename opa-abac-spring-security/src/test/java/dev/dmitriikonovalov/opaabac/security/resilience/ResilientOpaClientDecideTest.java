package dev.dmitriikonovalov.opaabac.security.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.DenyReason;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaDecision;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * U15 — {@link ResilientOpaClient#decide} is <strong>overridden</strong> and fail-closed (ADR 0030 §6).
 *
 * <p>The override is the whole case. Without it the decorator inherits {@code OpaClient}'s default,
 * which calls the decorator's <em>own</em> {@code allow} — so the delegate's reason is swallowed and
 * every step-up deny silently degrades to a plain deny, with nothing failing and nothing logged. The
 * first test fails if the override is ever removed: the default would never reach the delegate's
 * {@code decide}, and the guard would see a call that returns a boolean rather than a decision.
 */
class ResilientOpaClientDecideTest {

    private static final DenyReason STEP_UP =
            new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2", 300);

    private static AbacContext context() {
        return new AbacContext(
                new AbacContext.Subject("sup-anna", List.of(), Map.of()),
                "category:view",
                new AbacContext.Resource("category", "k-1", Map.of()),
                Map.of());
    }

    // --- the override exists ------------------------------------------------------------

    @Test // decide() reaches the DELEGATE's decide(), through the guard — not the inherited default
    void decide_isOverriddenAndGuarded() {
        CountingGuard guard = new CountingGuard();
        RecordingClient delegate = new RecordingClient(new OpaDecision(false, STEP_UP));

        OpaDecision decision = new ResilientOpaClient(delegate, guard).decide(context());

        assertThat(guard.calls.get()).as("the call went through the CallGuard").isEqualTo(1);
        assertThat(delegate.decideCalls.get()).as("the DELEGATE's decide was called").isEqualTo(1);
        assertThat(delegate.allowCalls.get()).as("not the inherited default's allow path").isZero();
        assertThat(decision.denyReason()).isEqualTo(STEP_UP);
    }

    @Test // the happy path passes the delegate's reason through unchanged
    void decide_passesTheDelegatesReasonThrough() {
        ResilientOpaClient client = new ResilientOpaClient(
                new RecordingClient(new OpaDecision(false, STEP_UP)), new CountingGuard());

        assertThat(client.decide(context())).isEqualTo(new OpaDecision(false, STEP_UP));
    }

    @Test // …and an allow stays a plain allow
    void decide_passesAnAllowThrough() {
        ResilientOpaClient client = new ResilientOpaClient(
                new RecordingClient(new OpaDecision(true, null)), new CountingGuard());

        assertThat(client.decide(context()).allow()).isTrue();
    }

    // --- every fail-closed outcome: deny, and NEVER a fabricated reason ------------------

    @Test // breaker open: the delegate is never invoked; the decorator synthesizes the deny by hand
    void decide_breakerOpenDeniesWithNoReason() {
        RecordingClient delegate = new RecordingClient(new OpaDecision(false, STEP_UP));
        ResilientOpaClient client = new ResilientOpaClient(delegate, new BreakerOpenGuard());

        OpaDecision decision = client.decide(context());

        assertThat(decision).isEqualTo(OpaDecision.deny());
        assertThat(decision.denyReason()).isNull();
        assertThat(delegate.decideCalls.get()).isZero();
    }

    @Test // a transport failure: the delegate already swallowed it into (false, null) — nothing invents
    void decide_transportFailureDeniesWithNoReason() {
        ResilientOpaClient client = new ResilientOpaClient(
                new RecordingClient(OpaDecision.deny()), new CountingGuard());

        assertThat(client.decide(context())).isEqualTo(OpaDecision.deny());
    }

    @Test // retries exhausted: the guard hands back the delegate's last value, still reasonless
    void decide_exhaustedRetryDeniesWithNoReason() {
        RecordingClient delegate = new RecordingClient(OpaDecision.deny());
        ResilientOpaClient client = new ResilientOpaClient(delegate, new RetryingGuard(2));

        OpaDecision decision = client.decide(context());

        assertThat(decision).isEqualTo(OpaDecision.deny());
        assertThat(delegate.decideCalls.get()).as("the sentinel was retried").isEqualTo(2);
    }

    @Test // a delegate that answers null cannot make the decorator NPE its way past the deny
    void decide_nullFromTheGuardIsADeny() {
        ResilientOpaClient client = new ResilientOpaClient(
                new RecordingClient(null), new CountingGuard());

        assertThat(client.decide(context())).isEqualTo(OpaDecision.deny());
    }

    // --- guards -------------------------------------------------------------------------

    /** Runs the body once, exactly as a healthy guard does, and counts. */
    private static final class CountingGuard implements CallGuard {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> T call(Supplier<T> body, Predicate<Throwable> retryableError, Predicate<T> retryableResult) {
            calls.incrementAndGet();
            return body.get();
        }
    }

    /** Short-circuits without invoking the body — the breaker-open contract. */
    private static final class BreakerOpenGuard implements CallGuard {
        @Override
        public <T> T call(Supplier<T> body, Predicate<Throwable> retryableError, Predicate<T> retryableResult) {
            throw new CallNotPermittedException("opa", null);
        }
    }

    /** Re-runs the body while the caller's predicate says the result is the fail-closed sentinel. */
    private record RetryingGuard(int attempts) implements CallGuard {
        @Override
        public <T> T call(Supplier<T> body, Predicate<Throwable> retryableError, Predicate<T> retryableResult) {
            T last = body.get();
            for (int i = 1; i < attempts && retryableResult.test(last); i++) {
                last = body.get();
            }
            return last;
        }
    }

    /** A delegate that answers a fixed decision and records which method was asked. */
    private static final class RecordingClient implements OpaClient {
        private final OpaDecision decision;
        private final AtomicInteger decideCalls = new AtomicInteger();
        private final AtomicInteger allowCalls = new AtomicInteger();

        private RecordingClient(OpaDecision decision) {
            this.decision = decision;
        }

        @Override
        public boolean allow(AbacContext context) {
            allowCalls.incrementAndGet();
            return decision != null && decision.allow();
        }

        @Override
        public OpaDecision decide(AbacContext context) {
            decideCalls.incrementAndGet();
            return decision;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.error();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(c -> false).toList();
        }
    }
}
