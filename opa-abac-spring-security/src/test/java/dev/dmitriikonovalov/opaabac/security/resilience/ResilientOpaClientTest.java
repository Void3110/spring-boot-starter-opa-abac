package dev.dmitriikonovalov.opaabac.security.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.HttpOpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClientConfig;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.PerTypePolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.PolicyEngineException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link ResilientOpaClient} contract after ADR 0037 §4 (ENGINE-ERRORS U13–U17, U34, U35; amends the
 * B3 U3/U4/U5 identity): the decorator retries <strong>thrown transient faults only</strong> and never a
 * returned decision; an exhausted retry rethrows the delegate's own {@link PolicyEngineException}; an open
 * breaker throws kind {@code CIRCUIT_OPEN} without touching the delegate. The identity cells run a real
 * {@code HttpOpaClient} against an in-process {@code HttpServer} stub (no WireMock); all timing is virtual
 * except the one cell that needs the production sleeper.
 */
class ResilientOpaClientTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int statusToReturn = 503; // a transient failure by default
    private volatile String bodyToReturn = "{}";

    private final MutableClock clock = MutableClock.startingAtEpoch();
    private final java.util.function.LongConsumer advancingSleeper = clock::advanceMillis;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusToReturn, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private OpaClient plainClient() {
        OpaClientConfig config = new OpaClientConfig(
                "http://127.0.0.1:" + port, Duration.ofMillis(500), "allow");
        return new HttpOpaClient(new ObjectMapper(), new PerTypePolicyPathResolver(""), config);
    }

    private CallGuard guard(ResilienceConfig config) {
        return new Resilience4jCallGuard("opa", config, clock, advancingSleeper);
    }

    /** 1 retry, breaker opens after 3 failures — the OPA-edge default shape. */
    private static ResilienceConfig opaBudget() {
        return new ResilienceConfig(true, 1, Duration.ofMillis(50), Duration.ofSeconds(3),
                3, Duration.ofSeconds(5), 1);
    }

    /** No retry, breaker opens after 3 failures and stays open for the test. */
    private static ResilienceConfig breakerAfterThree() {
        return new ResilienceConfig(true, 0, Duration.ofMillis(50), Duration.ofSeconds(3),
                3, Duration.ofSeconds(30), 1);
    }

    private static AbacContext ctx() {
        return new AbacContext(
                new AbacContext.Subject("u", List.of(), java.util.Map.of()),
                "catalog:view",
                new AbacContext.Resource("catalog", "c-1", java.util.Map.of()),
                java.util.Map.of());
    }

    private static PolicyEngineException thrownBy(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(PolicyEngineException.class);
        return (PolicyEngineException) thrown;
    }

    // --- Identity: an exhausted retry rethrows what the plain client throws (U15) -----------

    @Test // all four methods: a sustained 503 → the same HTTP_STATUS 503 from the plain client and the
    // decorator, which tried exactly twice (1 retry) before giving up. A fresh decorator per method: two
    // exhausted calls on one guard record four faults and open its breaker (threshold 3).
    void sustainedTransientFault_exhaustsTheRetry_andRethrowsTheSameKind() {
        List<java.util.function.Function<OpaClient, ThrowingCallable>> methods = List.of(
                c -> () -> c.allow(ctx()),
                c -> () -> c.decide(ctx()),
                c -> () -> c.compile(ctx()),
                c -> () -> c.allowAll(List.of(ctx(), ctx())));

        for (int i = 0; i < methods.size(); i++) {
            PolicyEngineException fromPlain = thrownBy(methods.get(i).apply(plainClient()));
            requestCount.set(0);
            ResilientOpaClient decorated = new ResilientOpaClient(plainClient(), guard(opaBudget()));
            PolicyEngineException fromDecorated = thrownBy(methods.get(i).apply(decorated));

            assertThat(fromDecorated.kind()).isEqualTo(fromPlain.kind()).isEqualTo(PolicyEngineException.Kind.HTTP_STATUS);
            assertThat(fromDecorated.httpStatus()).hasValue(503);
            assertThat(requestCount.get()).as("call %d: one attempt + one retry", i).isEqualTo(2);
        }
    }

    @Test // U14 — a transient blip recovering within budget → the decorator returns the REAL policy answer
    void allow_recoversWithinBudget() {
        // First request 503 (transient), the stub then flips to a 200 allow=true.
        server.removeContext("/");
        AtomicInteger n = new AtomicInteger();
        server.createContext("/", exchange -> {
            int attempt = n.incrementAndGet();
            byte[] body = (attempt < 2 ? "{}" : "{\"result\":{\"allow\":true}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt < 2 ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ResilientOpaClient decorated = new ResilientOpaClient(plainClient(), guard(opaBudget()));

        assertThat(decorated.allow(ctx())).as("the retry recovered the real allow=true").isTrue();
        assertThat(n.get()).isEqualTo(2);
    }

    @Test // U15 — a non-transient kind is rethrown at once: a 4xx, and a policy that is not loaded
    void nonTransientFaults_areNotRetried() {
        ResilientOpaClient decorated = new ResilientOpaClient(plainClient(), guard(opaBudget()));

        statusToReturn = 400;
        assertThat(thrownBy(() -> decorated.allow(ctx())).httpStatus()).hasValue(400);
        assertThat(requestCount.get()).isEqualTo(1);

        requestCount.set(0);
        statusToReturn = 200;
        bodyToReturn = "{}";
        assertThat(thrownBy(() -> decorated.allow(ctx())).kind()).isEqualTo(PolicyEngineException.Kind.UNDEFINED_DECISION);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    // --- U13: a returned decision is never retried -------------------------------------------

    @Test // every kind of deny — and a mixed page — is the delegate's ONE answer, returned unchanged
    void decisions_areCalledExactlyOnce() {
        ScriptedOpaClient denying = new ScriptedOpaClient();
        ResilientOpaClient decorated = new ResilientOpaClient(denying, guard(opaBudget()));

        assertThat(decorated.allow(ctx())).isFalse();
        assertThat(decorated.compile(ctx())).isEqualTo(PartialResult.denyAll());
        assertThat(decorated.allowAll(List.of(ctx(), ctx()))).containsExactly(false, false);
        denying.bulkAnswer = List.of(true, false, true);
        assertThat(decorated.allowAll(List.of(ctx(), ctx(), ctx()))).containsExactly(true, false, true);

        assertThat(denying.allowCalls).isEqualTo(1);
        assertThat(denying.compileCalls).isEqualTo(1);
        assertThat(denying.allowAllCalls).as("the all-false page and the mixed page, once each").isEqualTo(2);
    }

    // --- U16: breaker open → CIRCUIT_OPEN, the delegate untouched ----------------------------

    @Test
    void breakerOpen_throwsCircuitOpen_withoutCallingTheDelegate() {
        ScriptedOpaClient delegate = new ScriptedOpaClient();
        ResilientOpaClient decorated = new ResilientOpaClient(delegate, guard(breakerAfterThree()));

        delegate.fault = PolicyEngineException.transport("opa down", new IOException("refused"));
        for (int i = 0; i < 3; i++) {
            assertThat(thrownBy(() -> decorated.allow(ctx())).kind()).isEqualTo(PolicyEngineException.Kind.TRANSPORT);
        }
        delegate.fault = null; // the breaker is now open; from here the delegate must not be touched
        int allowCallsBeforeOpen = delegate.allowCalls;

        List<ThrowingCallable> calls = List.of(
                () -> decorated.allow(ctx()), () -> decorated.decide(ctx()),
                () -> decorated.compile(ctx()), () -> decorated.allowAll(List.of(ctx(), ctx())));
        for (ThrowingCallable call : calls) {
            PolicyEngineException e = thrownBy(call);
            assertThat(e.kind()).isEqualTo(PolicyEngineException.Kind.CIRCUIT_OPEN);
            assertThat(e).hasCauseInstanceOf(CallNotPermittedException.class);
        }

        assertThat(delegate.allowCalls).isEqualTo(allowCallsBeforeOpen);
        assertThat(delegate.decideCalls).isZero();
        assertThat(delegate.compileCalls).isZero();
        assertThat(delegate.allowAllCalls).isZero();
    }

    // --- U17 / U34: what the breaker counts ------------------------------------------------

    @Test // U17 — a stream of GENUINE policy denies must NOT open the breaker: never a decision input
    void genuineDenials_doNotOpenTheBreaker() {
        ScriptedOpaClient denying = new ScriptedOpaClient();
        Resilience4jCallGuard guard = new Resilience4jCallGuard("opa", opaBudget(), clock, advancingSleeper);
        ResilientOpaClient decorated = new ResilientOpaClient(denying, guard);

        for (int i = 0; i < 20; i++) {
            assertThat(decorated.allow(ctx())).isFalse();
        }

        assertThat(guard.breaker().getState())
                .as("genuine denials must not open the breaker (never a decision input)")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(denying.allowCalls).as("one call per deny — no retry, no short-circuit").isEqualTo(20);
    }

    @Test // U34 — the accepted consequence (ADR 0037 §4): the guard records EVERY thrown fault, so a
    // sustained deterministic fault (a policy that never loaded) opens the breaker like an outage does
    void sustainedUndefinedDecision_opensTheBreaker() {
        ScriptedOpaClient delegate = new ScriptedOpaClient();
        Resilience4jCallGuard guard = new Resilience4jCallGuard("opa", breakerAfterThree(), clock, advancingSleeper);
        ResilientOpaClient decorated = new ResilientOpaClient(delegate, guard);

        delegate.fault = PolicyEngineException.undefinedDecision("no policy at this path");
        for (int i = 0; i < 3; i++) {
            assertThat(thrownBy(() -> decorated.allow(ctx())).kind())
                    .isEqualTo(PolicyEngineException.Kind.UNDEFINED_DECISION);
        }

        assertThat(guard.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(thrownBy(() -> decorated.allow(ctx())).kind()).isEqualTo(PolicyEngineException.Kind.CIRCUIT_OPEN);
        assertThat(delegate.allowCalls).isEqualTo(3);
    }

    // --- U35: an interrupt during the backoff is INTERRUPTED, not CIRCUIT_OPEN --------------

    @Test // the production sleeper: the delegate's transient fault arrives with the thread interrupted, so the
    // guard's backoff Thread.sleep throws at once
    void interruptDuringBackoff_isInterrupted_notCircuitOpen() {
        ScriptedOpaClient delegate = new ScriptedOpaClient();
        delegate.fault = PolicyEngineException.transport("opa down", new IOException("reset"));
        delegate.interruptOnFault = true;
        ResilientOpaClient decorated =
                new ResilientOpaClient(delegate, new Resilience4jCallGuard("opa", opaBudget()));

        try {
            PolicyEngineException e = thrownBy(() -> decorated.allow(ctx()));

            assertThat(e.kind()).isEqualTo(PolicyEngineException.Kind.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).as("the flag survives").isTrue();
            assertThat(delegate.allowCalls).as("no retry after the interrupted backoff").isEqualTo(1);
        } finally {
            Thread.interrupted(); // never leave the test thread interrupted
        }
    }

    /**
     * A delegate that answers a genuine deny on every method (all-false / {@code denyAll()} for the filtering
     * pair) unless a {@link #fault} is set, which it then throws; counts every invocation.
     */
    private static final class ScriptedOpaClient implements OpaClient {
        volatile RuntimeException fault;
        volatile boolean interruptOnFault;
        volatile List<Boolean> bulkAnswer;
        int allowCalls;
        int decideCalls;
        int compileCalls;
        int allowAllCalls;

        @Override
        public boolean allow(AbacContext context) {
            allowCalls++;
            throwIfFaulted();
            return false;
        }

        @Override
        public dev.dmitriikonovalov.opaabac.core.OpaDecision decide(AbacContext context) {
            decideCalls++;
            throwIfFaulted();
            return dev.dmitriikonovalov.opaabac.core.OpaDecision.deny();
        }

        @Override
        public PartialResult compile(AbacContext context) {
            compileCalls++;
            throwIfFaulted();
            return PartialResult.denyAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            allowAllCalls++;
            throwIfFaulted();
            return bulkAnswer != null ? bulkAnswer : java.util.Collections.nCopies(contexts.size(), false);
        }

        private void throwIfFaulted() {
            if (fault != null) {
                if (interruptOnFault) {
                    Thread.currentThread().interrupt();
                }
                throw fault;
            }
        }
    }
}
