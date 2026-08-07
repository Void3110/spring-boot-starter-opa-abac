package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.SupervisedScopeClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * U17–U23 — {@link SupervisedScopeClient}'s classification, against an in-process {@link HttpServer} stub
 * (no WireMock), mirroring {@code HttpGovernedScopeResolverTest}.
 *
 * <p>The whole point: <b>every</b> non-affirmative outcome — an empty array, a 4xx/5xx, a blank or
 * unparseable body, a non-UUID element, a timeout, a connection failure, an interrupt — returns the
 * <b>empty list</b> without throwing. A throw here would 500 the catalog list instead of degrading the
 * subject to their own memberships, and a <em>partial</em> parse would be worse still: a partial supervised
 * set is indistinguishable from a correct smaller one. U19–U23 are the ones that matter.
 *
 * <p>The resilience behaviour of this edge (retry, and the invariant that a fail-closed empty result is
 * <b>not</b> a breaker failure — U24) lives in {@code EdgeResilienceTest}, where the virtual clock is.
 */
class SupervisedScopeClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(StubHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SupervisedScopeClient clientFor(String baseUrl) {
        return new SupervisedScopeClient(MAPPER, baseUrl, 500);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    // --- U17 — 200 + two uuids → both parsed, distinct, order-independent ----------------------------

    @Test
    void parsesTwoSupervisedIds() throws IOException {
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String base = startServer(ex -> respond(ex, 200, "[\"" + id2 + "\",\"" + id1 + "\"]"));

        List<UUID> ids = clientFor(base).supervisedIds("sup-anna", "catalog");

        assertThat(ids).containsExactlyInAnyOrder(id1, id2).doesNotHaveDuplicates();
    }

    // --- U18 — 200 + [] → the authoritative "supervises nothing" -------------------------------------

    @Test
    void emptyArrayIsTheAuthoritativeNothing() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "[]"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    // --- U19 — 500 / 404 / 401 → empty, no throw escaping to a 500 -----------------------------------

    @Test
    void serverError500IsEmpty_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    @Test
    void notFound404IsEmpty_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 404, "nope"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    @Test
    void unauthorized401IsEmpty_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 401, "denied"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    // --- U20 — 200 + a blank body → empty -----------------------------------------------------------

    @Test
    void blankBodyIsEmpty_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 200, ""));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    // --- U21 — unparseable JSON / a malformed element → empty, NEVER a partial list ------------------

    @Test
    void unparseableBodyIsEmpty_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not json {{{"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    @Test
    void malformedElementDiscardsTheWholeList() throws IOException {
        // [123, "not-a-uuid"] — a number and a non-UUID string. Either alone must empty the result: a
        // partial supervised set is indistinguishable from a correct smaller one.
        String base = startServer(ex -> respond(ex, 200, "[123,\"not-a-uuid\"]"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    @Test
    void oneBadElementAmongGoodOnesDiscardsTheWholeList() throws IOException {
        String base = startServer(ex -> respond(
                ex, 200, "[\"11111111-1111-1111-1111-111111111111\",\"not-a-uuid\"]"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    @Test
    void nullElementDiscardsTheWholeList() throws IOException {
        String base = startServer(ex -> respond(
                ex, 200, "[\"11111111-1111-1111-1111-111111111111\",null]"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    @Test
    void nonArrayBodyIsEmpty_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"ids\":[]}"));
        assertThat(clientFor(base).supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    // --- U22 — the stub never responds (timeout) → empty within the configured timeout ---------------

    @Test
    void timeoutIsEmpty_threadNotWedged() throws IOException {
        // The stub stalls on a latch nothing releases — an unresponsive user-service, well past the
        // client's 500ms request timeout, without a wall-clock sleep in the handler.
        CountDownLatch neverReleased = new CountDownLatch(1);
        String base = startServer(ex -> {
            try {
                neverReleased.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "[]");
        });

        long startedAt = System.nanoTime();
        List<UUID> ids = clientFor(base).supervisedIds("sup-anna", "catalog");
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(ids).isEmpty();
        assertThat(elapsedMillis).as("returned on the request timeout, not on the stub's 2s sleep")
                .isLessThan(1500);
    }

    @Test
    void connectionRefusedIsEmpty_noThrow() {
        // Nothing listening on :1 → transport failure → empty, no throw.
        assertThat(clientFor("http://127.0.0.1:1").supervisedIds("sup-anna", "catalog")).isEmpty();
    }

    // --- U23 — interrupted mid-call → empty, and the interrupt flag is RESTORED ----------------------

    @Test
    void interruptedMidCallIsEmpty_andTheFlagIsRestored() throws Exception {
        // The stub signals as soon as the exchange is underway, then stalls on a latch the test never
        // releases — so the interrupt lands mid-call deterministically, with no sleep-to-synchronize.
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        String base = startServer(ex -> {
            requestArrived.countDown();
            try {
                neverReleased.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "[]");
        });
        SupervisedScopeClient client = new SupervisedScopeClient(MAPPER, base, 20_000);

        AtomicReference<List<UUID>> result = new AtomicReference<>();
        AtomicBoolean flagRestored = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            result.set(client.supervisedIds("sup-anna", "catalog"));
            flagRestored.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertThat(requestArrived.await(10, TimeUnit.SECONDS)).as("the exchange started").isTrue();
        caller.interrupt();
        caller.join(10_000);

        assertThat(result.get()).as("an interrupt fails closed to empty, never a throw").isEmpty();
        assertThat(flagRestored.get()).as("the interrupt flag is restored before failing closed").isTrue();
    }

    // --- null / blank coordinates → empty, and NO call is made ---------------------------------------

    @Test
    void blankCoordinatesAreEmpty_noCall() throws IOException {
        AtomicBoolean called = new AtomicBoolean();
        String base = startServer(ex -> {
            called.set(true);
            respond(ex, 200, "[\"11111111-1111-1111-1111-111111111111\"]");
        });
        SupervisedScopeClient client = clientFor(base);

        assertThat(client.supervisedIds(null, "catalog")).isEmpty();
        assertThat(client.supervisedIds("  ", "catalog")).isEmpty();
        assertThat(client.supervisedIds("sup-anna", null)).isEmpty();
        assertThat(client.supervisedIds("sup-anna", " ")).isEmpty();
        assertThat(called.get()).isFalse();
    }

    // --- request shape (the endpoint T1 ships, mirroring /internal/governed-targets) -----------------

    @Test
    void sendsCorrectRequestUrl() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        String base = startServer(ex -> {
            path.set(ex.getRequestURI().getPath());
            query.set(ex.getRequestURI().getQuery());
            respond(ex, 200, "[]");
        });

        clientFor(base).supervisedIds("sup-anna", "catalog");

        assertThat(path.get()).isEqualTo("/internal/supervised-targets");
        assertThat(query.get()).contains("subject=sup-anna").contains("resourceType=catalog");
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
