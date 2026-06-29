package dev.dmitriikonovalov.opaabac.security.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DiscoveryOwnershipResolver} (Slice B4 QA U5–U10) against an in-process
 * {@link HttpServer} stub — no WireMock — and a hand-advanced {@link Clock} for the TTL cases (zero
 * {@code Thread.sleep}, no wall-clock assertions). The keystones: U7 (unknown type → false, no call),
 * U8 (unreachable/404 → false), U9 (cache hit → no second call), U10 (TTL expiry → re-fetch).
 */
class DiscoveryOwnershipResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OWNER = "sub-owner-1";
    private static final UUID RESOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a stub that counts requests and runs the supplied handler. Returns its base URL. */
    private String startServer(StubHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private OwnershipProperties propsFor(String catalogUrl, Duration ttl) {
        OwnershipProperties p = new OwnershipProperties();
        if (catalogUrl != null) {
            p.setServices(Map.of("catalog", catalogUrl));
        }
        p.setTtl(ttl);
        p.setTimeoutMs(500);
        return p;
    }

    private DiscoveryOwnershipResolver resolver(OwnershipProperties props, Clock clock) {
        return new DiscoveryOwnershipResolver(props, MAPPER, clock);
    }

    // --- U5 — owner sub matches createdBy → true ----------------------------------------------------

    @Test
    void ownerMatches_isTrue() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"createdBy\":\"" + OWNER + "\"}"));
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), Clock.systemUTC());
        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isTrue();
    }

    // --- U6 — caller != createdBy → false -----------------------------------------------------------

    @Test
    void nonOwner_isFalse() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"createdBy\":\"" + OWNER + "\"}"));
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), Clock.systemUTC());
        assertThat(resolver.isOwner("sub-someone-else", "catalog", RESOURCE)).isFalse();
    }

    // --- U7 — unknown type (no registry entry) → false, NO call -------------------------------------

    @Test
    void unknownType_isFalse_noCall() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"createdBy\":\"" + OWNER + "\"}"));
        // Registry maps "catalog" only; ask about "product" → no entry → false, never hits the server.
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), Clock.systemUTC());
        assertThat(resolver.isOwner(OWNER, "product", RESOURCE)).isFalse();
        assertThat(calls.get()).isZero();
    }

    // --- U8 — unreachable / 5xx / 404 → false -------------------------------------------------------

    @Test
    void notFound404_isFalse() throws IOException {
        String base = startServer(ex -> respond(ex, 404, ""));
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), Clock.systemUTC());
        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isFalse();
    }

    @Test
    void serverError5xx_isFalse() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), Clock.systemUTC());
        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isFalse();
    }

    @Test
    void unreachable_isFalse() {
        // Nothing listening on :1 → transport failure → false, no throw.
        var resolver = resolver(propsFor("http://127.0.0.1:1", Duration.ofSeconds(30)), Clock.systemUTC());
        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isFalse();
    }

    @Test
    void blankOrMalformedBody_isFalse() throws IOException {
        String blank = startServer(ex -> respond(ex, 200, ""));
        assertThat(resolver(propsFor(blank, Duration.ofSeconds(30)), Clock.systemUTC())
                .isOwner(OWNER, "catalog", RESOURCE)).isFalse();
        server.stop(0);

        String malformed = startServer(ex -> respond(ex, 200, "not json {{{"));
        assertThat(resolver(propsFor(malformed, Duration.ofSeconds(30)), Clock.systemUTC())
                .isOwner(OWNER, "catalog", RESOURCE)).isFalse();
    }

    @Test
    void blankSubjectOrNullCoords_isFalse_noCall() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"createdBy\":\"" + OWNER + "\"}"));
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), Clock.systemUTC());
        assertThat(resolver.isOwner("  ", "catalog", RESOURCE)).isFalse();
        assertThat(resolver.isOwner(OWNER, "catalog", null)).isFalse();
        assertThat(calls.get()).isZero();
    }

    // --- U9 — cache hit: a second isOwner for the same (type,id) makes NO second HTTP call -----------

    @Test
    void cacheHit_noSecondCall() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"createdBy\":\"" + OWNER + "\"}"));
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), Clock.systemUTC());

        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isTrue();
        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isTrue(); // served from cache
        // A different subject for the SAME resource also hits the cache (key is (type,id), not subject).
        assertThat(resolver.isOwner("sub-other", "catalog", RESOURCE)).isFalse();
        assertThat(calls.get()).isEqualTo(1); // exactly ONE fetch despite three checks
    }

    // --- U10 — TTL expiry: after the clock advances past the TTL, the next check re-fetches -----------

    @Test
    void ttlExpiry_reFetches() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"createdBy\":\"" + OWNER + "\"}"));
        MutableTestClock clock = new MutableTestClock(Instant.EPOCH);
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), clock);

        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isTrue();
        assertThat(calls.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(29)); // still within TTL → cache
        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isTrue();
        assertThat(calls.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2)); // now 31s > 30s TTL → expired → re-fetch
        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isTrue();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test // a transient failure is NOT cached — a recovered service answers correctly on the next try
    void outageNotCached_recoversOnNextCall() throws IOException {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        String base = startServer(ex -> {
            if (n.getAndIncrement() == 0) {
                respond(ex, 500, "boom"); // first call: outage
            } else {
                respond(ex, 200, "{\"createdBy\":\"" + OWNER + "\"}"); // recovered
            }
        });
        var resolver = resolver(propsFor(base, Duration.ofSeconds(30)), Clock.systemUTC());

        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isFalse(); // outage → false (not cached)
        assertThat(resolver.isOwner(OWNER, "catalog", RESOURCE)).isTrue(); // recovered → re-fetched, owner
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** A hand-advanced clock for the TTL cases (local to this test; mirrors the resilience MutableClock). */
    private static final class MutableTestClock extends Clock {
        private Instant now;

        MutableTestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public long millis() {
            return now.toEpochMilli();
        }
    }
}
