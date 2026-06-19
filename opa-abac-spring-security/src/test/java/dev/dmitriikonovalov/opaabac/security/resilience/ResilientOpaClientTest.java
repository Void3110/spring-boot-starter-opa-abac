package dev.dmitriikonovalov.opaabac.security.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.HttpOpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaClientConfig;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.PerTypePolicyPathResolver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * U3/U4/U5 — the {@link ResilientOpaClient} fail-closed contract (ADR 0017 §2). The decorator's value on a
 * sustained failure (exhausted retry) and on a forced-open breaker must be <strong>identical</strong> to the
 * plain {@link HttpOpaClient}'s — {@code allow}→{@code false}, {@code compile}→{@link PartialResult#error()}
 * ({@code fromError==true}), {@code allowAll}→n×{@code false} — and {@code compile} is <em>never</em>
 * {@code denyAll()}/{@code allowAll()} (the 5.5-B hierarchy-widening landmine). The delegate is a real
 * {@code HttpOpaClient} against an in-process {@code HttpServer} stub (no WireMock); all timing is virtual.
 */
class ResilientOpaClientTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int statusToReturn = 503; // a transient failure by default

    private final MutableClock clock = MutableClock.startingAtEpoch();
    private final java.util.function.LongConsumer advancingSleeper = millis -> clock.advanceMillis(millis);

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
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

    private static AbacContext ctx() {
        return new AbacContext(
                new AbacContext.Subject("u", List.of(), java.util.Map.of()),
                "catalog:view",
                new AbacContext.Resource("catalog", "c-1", java.util.Map.of()),
                java.util.Map.of());
    }

    // --- U3: fail-closed identity (exhausted retry == plain delegate) -------------------

    @Test // allow: decorator exhausted == plain client failure == false
    void allow_failClosedIdentity() {
        OpaClient plain = plainClient();
        ResilientOpaClient decorated = new ResilientOpaClient(plainClient(), guard(opaBudget()));

        boolean plainValue = plain.allow(ctx());
        boolean decoratedValue = decorated.allow(ctx());

        assertThat(plainValue).isFalse();
        assertThat(decoratedValue).isEqualTo(plainValue).isFalse();
    }

    @Test // compile: decorator exhausted == plain client failure == error() with fromError=true
    void compile_failClosedIdentity_isErrorNotDenyAll() {
        OpaClient plain = plainClient();
        ResilientOpaClient decorated = new ResilientOpaClient(plainClient(), guard(opaBudget()));

        PartialResult plainValue = plain.compile(ctx());
        PartialResult decoratedValue = decorated.compile(ctx());

        // both are error() — DENY_ALL with fromError=true
        assertThat(plainValue.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(plainValue.fromError()).isTrue();
        assertThat(decoratedValue.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(decoratedValue.fromError()).as("U5: fromError MUST be true on the failure path").isTrue();
        assertThat(decoratedValue).isEqualTo(plainValue);
    }

    @Test // allowAll: decorator exhausted == plain client failure == n × false
    void allowAll_failClosedIdentity() {
        OpaClient plain = plainClient();
        ResilientOpaClient decorated = new ResilientOpaClient(plainClient(), guard(opaBudget()));
        List<AbacContext> batch = List.of(ctx(), ctx(), ctx());

        List<Boolean> plainValue = plain.allowAll(batch);
        List<Boolean> decoratedValue = decorated.allowAll(batch);

        assertThat(plainValue).containsExactly(false, false, false);
        assertThat(decoratedValue).isEqualTo(plainValue).containsExactly(false, false, false);
    }

    @Test // a transient blip recovering within budget → the decorator returns the REAL policy answer
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

    // --- U4 + U5: breaker OPEN synthesizes the fail-closed value WITHOUT the delegate ----

    @Test // force the breaker open (via a thrown FAULT, the only thing that may), then assert all three
    // methods fail closed without calling the delegate
    void breakerOpen_synthesizesFailClosed_withoutDelegate() {
        // a counting delegate so we can prove it is NOT invoked while the breaker is open
        CountingOpaClient counting = new CountingOpaClient();
        CallGuard sharedGuard = guard(new ResilienceConfig(true, 0, Duration.ofMillis(50),
                Duration.ofSeconds(3), 3, Duration.ofSeconds(30), 1));
        ResilientOpaClient decorated = new ResilientOpaClient(counting, sharedGuard);

        // 3 thrown faults open the breaker. A returned deny sentinel would NOT (a decision must never feed
        // the breaker — ADR 0017 §5); only an unambiguous thrown fault does. In production the OPA delegate
        // swallows faults to the sentinel and never throws, so the OPA breaker is effectively a no-op — this
        // test drives it through the guard directly to prove the breaker-OPEN fail-closed synthesis still holds.
        counting.throwFault = true;
        for (int i = 0; i < 3; i++) {
            try {
                decorated.allow(ctx());
            } catch (RuntimeException expected) {
                // the guard re-throws the exhausted fault (maxRetries=0) — ResilientOpaClient.allow does not
                // catch a generic RuntimeException, only CallNotPermittedException, so it propagates here
            }
        }
        counting.throwFault = false; // the breaker is now open; from here the delegate must not be touched
        int allowCallsBeforeOpen = counting.allowCalls;

        // now the breaker is open: every method fails closed WITHOUT touching the delegate
        boolean allow = decorated.allow(ctx());
        PartialResult compile = decorated.compile(ctx());
        List<Boolean> allowAll = decorated.allowAll(List.of(ctx(), ctx()));

        assertThat(allow).isFalse();
        assertThat(compile.decision()).isEqualTo(PartialResult.Decision.DENY_ALL);
        assertThat(compile.fromError()).as("U5: breaker-open compile is error() (fromError), never denyAll()")
                .isTrue();
        assertThat(compile).isEqualTo(PartialResult.error());
        assertThat(compile).isNotEqualTo(PartialResult.denyAll()); // the landmine
        assertThat(compile).isNotEqualTo(PartialResult.allowAll()); // the catastrophe value
        assertThat(allowAll).containsExactly(false, false);

        // the delegate was NOT invoked for any of the three breaker-open calls
        assertThat(counting.allowCalls).isEqualTo(allowCallsBeforeOpen);
        assertThat(counting.compileCalls).isZero();
        assertThat(counting.allowAllCalls).isZero();
    }

    @Test // P5 — the DECISION path: a stream of GENUINE policy denies (delegate returns a real false, not a
    // fault) must NOT open the OPA breaker. The breaker is never a decision input (ADR 0017 §5).
    void genuineDenials_doNotOpenTheBreaker() {
        CountingOpaClient counting = new CountingOpaClient();
        counting.failClosed = true; // every allow returns a genuine policy DENY (false)
        Resilience4jCallGuard guard = new Resilience4jCallGuard("opa",
                new ResilienceConfig(true, 1, Duration.ofMillis(50), Duration.ofSeconds(3),
                        3, Duration.ofSeconds(30), 1),
                clock, advancingSleeper);
        ResilientOpaClient decorated = new ResilientOpaClient(counting, guard);

        // far more consecutive denials than failureThreshold(=3), each at 2 attempts (maxRetries=1)
        for (int i = 0; i < 20; i++) {
            assertThat(decorated.allow(ctx())).isFalse();
        }

        // the breaker stayed CLOSED — denials are decisions, not breaker faults; the delegate was reached
        // on every call (no short-circuit)
        assertThat(guard.breaker().getState())
                .as("genuine denials must not open the breaker (never a decision input)")
                .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        assertThat(counting.allowCalls).as("every call reached the delegate (breaker never short-circuited)")
                .isGreaterThanOrEqualTo(20);
    }

    /** A delegate that counts invocations and can return the fail-closed sentinel OR throw a fault. */
    private static final class CountingOpaClient implements OpaClient {
        volatile boolean failClosed = false;
        volatile boolean throwFault = false;
        int allowCalls = 0;
        int compileCalls = 0;
        int allowAllCalls = 0;

        @Override
        public boolean allow(AbacContext context) {
            allowCalls++;
            if (throwFault) {
                throw new java.io.UncheckedIOException(new java.io.IOException("opa down"));
            }
            return !failClosed;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            compileCalls++;
            if (throwFault) {
                throw new java.io.UncheckedIOException(new java.io.IOException("opa down"));
            }
            return failClosed ? PartialResult.error() : PartialResult.allowAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            allowAllCalls++;
            if (throwFault) {
                throw new java.io.UncheckedIOException(new java.io.IOException("opa down"));
            }
            return java.util.Collections.nCopies(contexts.size(), !failClosed);
        }
    }
}
