package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.HttpRoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.ResolveTarget;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.resilience.CallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.Resilience4jCallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.ResilienceConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpRoleDefinitionSupplier#lookupAll} against an in-process {@link HttpServer} stub — no
 * WireMock (QA case U10, ADR 0024): <strong>one exchange per batch</strong> (request-counted), B2's
 * strict classification batched, strict completeness (a missing/extra/duplicate entry is a
 * whole-batch outage), 5xx/429 transient inside the same resolve guard as ONE call, every 4xx
 * permanent with exactly one attempt, breaker-open without an exchange, empty set without HTTP,
 * URL-encoded target parts.
 */
class HttpRoleDefinitionSupplierBatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ResolveTarget CAT_A =
            new ResolveTarget("catalog", "aaaaaaaa-0000-0000-0000-000000000001");
    private static final ResolveTarget CAT_B =
            new ResolveTarget("catalog", "aaaaaaaa-0000-0000-0000-000000000002");

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    private final MutableClock clock = MutableClock.startingAtEpoch();
    private final java.util.function.LongConsumer advancingSleeper = clock::advanceMillis;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.incrementAndGet();
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The resolve-edge guard shape: 2 retries, breaker opens after 5 failures — virtual time. */
    private CallGuard guard() {
        ResilienceConfig cfg = new ResilienceConfig(true, 2, Duration.ofMillis(50), Duration.ofSeconds(6),
                5, Duration.ofSeconds(10), 1);
        return new Resilience4jCallGuard("resolve", cfg, clock, advancingSleeper);
    }

    private HttpRoleDefinitionSupplier guarded(String base) {
        return new HttpRoleDefinitionSupplier(MAPPER, base, 500, guard());
    }

    /** The unguarded (single-attempt) supplier — for the pure-classification rows. */
    private HttpRoleDefinitionSupplier plain(String base) {
        return new HttpRoleDefinitionSupplier(MAPPER, base, 500);
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

    private static String entry(ResolveTarget target, String roleJsonOrNull) {
        return "{\"resourceType\":\"" + target.resourceType() + "\","
                + "\"resourceId\":\"" + target.resourceId() + "\","
                + "\"role\":" + roleJsonOrNull + "}";
    }

    private static final String VALID_ROLE =
            "{\"code\":\"member\",\"attributes\":{\"role_level\":20},"
                    + "\"permissions\":{\"catalog\":[\"READ\"]}}";

    @Test // U10 — 200+complete → the map; role:null → Optional.empty(); ONE exchange for the batch
    void completeBatchResolvesInOneExchange() throws IOException {
        String base = startServer(ex ->
                respond(ex, 200, "[" + entry(CAT_A, VALID_ROLE) + "," + entry(CAT_B, "null") + "]"));

        Map<ResolveTarget, Optional<RoleDefinition>> result =
                guarded(base).lookupAll("sub-1", Set.of(CAT_A, CAT_B));

        assertThat(result).containsOnlyKeys(CAT_A, CAT_B);
        assertThat(result.get(CAT_A)).isPresent();
        assertThat(result.get(CAT_A).get().code()).isEqualTo("member");
        assertThat(result.get(CAT_B)).isEmpty(); // explicit null → authoritative no-role
        assertThat(requests.get()).as("one exchange per batch").isEqualTo(1);
    }

    @Test // U10 — the request carries userId + one target=<type>:<id> per target, parts URL-encoded
    void requestEncodesUserAndTargets() throws IOException {
        AtomicReference<String> query = new AtomicReference<>();
        String base = startServer(ex -> {
            query.set(ex.getRequestURI().getRawQuery());
            respond(ex, 200, "[" + entry(CAT_A, "null") + "]");
        });

        guarded(base).lookupAll("sub with space", Set.of(CAT_A));

        assertThat(query.get()).contains("userId=sub+with+space");
        // Each PART is form-encoded, the separator ':' is the literal joiner — a ':' inside a part
        // would itself arrive as %3A, so the <type>:<id> split can never be ambiguous.
        assertThat(query.get()).contains("target=catalog:" + CAT_A.resourceId());
    }

    @Test // U10 — a MISSING entry → whole-batch outage (strict completeness), permanent: 1 attempt
    void missingEntryIsWholeBatchOutage() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "[" + entry(CAT_A, "null") + "]"));

        assertThatThrownBy(() -> guarded(base).lookupAll("sub-1", Set.of(CAT_A, CAT_B)))
                .isInstanceOf(RoleResolutionException.class)
                .hasMessageContaining("incomplete");
        assertThat(requests.get()).as("malformed 200 is permanent — no retry").isEqualTo(1);
    }

    @Test // U10 — an EXTRA (unrequested) entry → same whole-batch outage
    void extraEntryIsWholeBatchOutage() throws IOException {
        ResolveTarget foreign = new ResolveTarget("catalog", "aaaaaaaa-0000-0000-0000-00000000000f");
        String base = startServer(ex -> respond(ex, 200,
                "[" + entry(CAT_A, "null") + "," + entry(foreign, "null") + "]"));

        assertThatThrownBy(() -> guarded(base).lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test // U10 — a DUPLICATE entry → same whole-batch outage
    void duplicateEntryIsWholeBatchOutage() throws IOException {
        String base = startServer(ex -> respond(ex, 200,
                "[" + entry(CAT_A, "null") + "," + entry(CAT_A, VALID_ROLE) + "]"));

        assertThatThrownBy(() -> guarded(base).lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class)
                .hasMessageContaining("duplicate");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test // U10 — 200-blank and unparseable bodies → permanent outage
    void blankAndUnparseableBodiesArePermanentOutages() throws IOException {
        String base = startServer(ex -> respond(ex, 200, ""));
        assertThatThrownBy(() -> guarded(base).lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).isEqualTo(1);
        server.stop(0);
        requests.set(0);

        String base2 = startServer(ex -> respond(ex, 200, "{not json"));
        assertThatThrownBy(() -> guarded(base2).lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test // U10 — a transient 5xx recovers within the guard's budget; the batch is the retry unit
    void transientRecoversWithinBudget() throws IOException {
        AtomicInteger n = new AtomicInteger();
        String base = startServer(ex -> {
            if (n.incrementAndGet() < 2) {
                respond(ex, 503, "down");
            } else {
                respond(ex, 200, "[" + entry(CAT_A, VALID_ROLE) + "]");
            }
        });

        Map<ResolveTarget, Optional<RoleDefinition>> result =
                guarded(base).lookupAll("sub-1", Set.of(CAT_A));

        assertThat(result.get(CAT_A)).isPresent();
        assertThat(requests.get()).as("one failed + one recovered exchange").isEqualTo(2);
    }

    @Test // U10 — exhausted 5xx → RoleResolutionException after the full budget (1 + 2 retries)
    void exhaustedTransientThrows() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));

        assertThatThrownBy(() -> guarded(base).lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test // U10 — 429 is transient too (retried), like the single-target path
    void tooManyRequestsIsTransient() throws IOException {
        String base = startServer(ex -> respond(ex, 429, "slow down"));

        assertThatThrownBy(() -> guarded(base).lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test // U10 — every 4xx (the server's malformed-target 400 included) → permanent, EXACTLY 1 attempt
    void clientErrorIsPermanentSingleAttempt() throws IOException {
        String base = startServer(ex -> respond(ex, 400, "malformed target"));

        assertThatThrownBy(() -> guarded(base).lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).as("4xx is permanent — no retry").isEqualTo(1);
    }

    @Test // U10 — a 204 is NOT part of the batch contract (no-role travels in-body) → permanent outage
    void noContentIsAnOutageForTheBatch() throws IOException {
        String base = startServer(ex -> respond(ex, 204, ""));

        assertThatThrownBy(() -> guarded(base).lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test // U10 — breaker open → RoleResolutionException WITHOUT an exchange
    void breakerOpenFailsClosedWithoutExchange() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        HttpRoleDefinitionSupplier supplier = guarded(base);

        // Two exhausted batches = 6 recorded failures → the breaker (threshold 5) opens.
        assertThatThrownBy(() -> supplier.lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        assertThatThrownBy(() -> supplier.lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class);
        int exchangesSoFar = requests.get();

        assertThatThrownBy(() -> supplier.lookupAll("sub-1", Set.of(CAT_A)))
                .isInstanceOf(RoleResolutionException.class)
                .hasMessageContaining("breaker open");
        assertThat(requests.get()).as("breaker open → the delegate is never called")
                .isEqualTo(exchangesSoFar);
    }

    @Test // U10 — empty target set → Map.of() with ZERO HTTP
    void emptyBatchMakesNoExchange() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "must never be called"));

        assertThat(guarded(base).lookupAll("sub-1", Set.of())).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test // the null-userId posture mirrors lookup(): authoritative all-empty, no HTTP
    void nullUserIdAnswersAllEmptyWithoutExchange() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "must never be called"));

        Map<ResolveTarget, Optional<RoleDefinition>> result =
                plain(base).lookupAll(null, Set.of(CAT_A, CAT_B));

        assertThat(result).containsOnlyKeys(CAT_A, CAT_B);
        assertThat(result.values()).allSatisfy(v -> assertThat(v).isEmpty());
        assertThat(requests.get()).isZero();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** A hand-advanced virtual clock (the EdgeResilienceTest idiom) — zero wall-clock dependence. */
    static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        static MutableClock startingAtEpoch() {
            return new MutableClock(Instant.EPOCH);
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
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
    }
}
