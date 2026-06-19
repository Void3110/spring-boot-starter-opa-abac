package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.HttpRoleDefinitionSupplier;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionClient;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionFetchException;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * U9/U10 — the B3 resilience wrappers over the two app edges (ADR 0017 §3), against an in-process
 * {@link HttpServer} stub (no WireMock), with an <strong>enabled</strong> guard at virtual time
 * (a hand-advanced clock + a no-op sleeper that advances it). B2 is preserved exactly: a transient blip
 * recovers within budget; an exhausted transient still throws; a {@code 4xx} (and 204/200-terminal) is
 * <em>not</em> retried (asserted by attempt count).
 */
class EdgeResilienceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    private final MutableClock clock = MutableClock.startingAtEpoch();
    private final java.util.function.LongConsumer advancingSleeper = millis -> clock.advanceMillis(millis);

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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** A guard with the resolve/tag default-ish shape: 2 retries, 50ms, 6s, breaker opens after 5. */
    private CallGuard guard(String name) {
        ResilienceConfig cfg = new ResilienceConfig(true, 2, Duration.ofMillis(50), Duration.ofSeconds(6),
                5, Duration.ofSeconds(10), 1);
        return new Resilience4jCallGuard(name, cfg, clock, advancingSleeper);
    }

    private HttpRoleDefinitionSupplier resolver(String base) {
        return new HttpRoleDefinitionSupplier(MAPPER, base, 500, guard("resolve"));
    }

    private TagDefinitionClient tagClient(String base) {
        return new TagDefinitionClient(MAPPER, base, 500, guard("tag"));
    }

    private static final String VALID_ROLE =
            "{\"code\":\"owner\",\"attributes\":{\"role_level\":40},"
                    + "\"permissions\":{\"catalog\":[\"READ\",\"WRITE\"]}}";

    // --- U9: resolve wrapper ------------------------------------------------------------

    @Test // U9a — k<budget transient 5xx then 200+valid → recovers to a resolved RoleDefinition
    void resolve_transientThenRecovers() throws IOException {
        AtomicInteger n = new AtomicInteger();
        String base = startServer(ex -> {
            int attempt = n.incrementAndGet();
            respond(ex, attempt < 2 ? 503 : 200, attempt < 2 ? "unavailable" : VALID_ROLE);
        });

        Optional<RoleDefinition> result = resolver(base).lookup("sub-1", "catalog", "c-1");

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("owner");
        assertThat(n.get()).as("recovered on the second attempt").isEqualTo(2);
    }

    @Test // U9b — a budget-exhausting 5xx → throws RoleResolutionException (B2 outcome) after all attempts
    void resolve_exhaustedTransientThrows() throws IOException {
        String base = startServer(ex -> respond(ex, 503, "down"));

        assertThatThrownBy(() -> resolver(base).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).as("3 attempts = 2 retries + 1").isEqualTo(3);
    }

    @Test // U9c — a 4xx → throws after EXACTLY ONE attempt (no retry; B2 invariant)
    void resolve_4xxThrowsImmediately() throws IOException {
        String base = startServer(ex -> respond(ex, 404, "not found"));

        assertThatThrownBy(() -> resolver(base).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(requests.get()).as("4xx is permanent — no retry").isEqualTo(1);
    }

    @Test // U9d — a 204 → Optional.empty() after EXACTLY ONE attempt (terminal no-role, not retried)
    void resolve_204Terminal_noRetry() throws IOException {
        String base = startServer(ex -> respond(ex, 204, ""));

        assertThat(resolver(base).lookup("sub-1", "catalog", "c-1")).isEmpty();
        assertThat(requests.get()).as("204 is the terminal no-role signal — no retry").isEqualTo(1);
    }

    @Test // U9e — a 200+valid → resolved after EXACTLY ONE attempt (terminal, not retried)
    void resolve_200ValidTerminal_noRetry() throws IOException {
        String base = startServer(ex -> respond(ex, 200, VALID_ROLE));

        assertThat(resolver(base).lookup("sub-1", "catalog", "c-1")).isPresent();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test // U9 — a transport failure (connection refused) is retried then throws on exhaustion
    void resolve_connectionRefused_retriesThenThrows() {
        // nothing listening on this port → connect refused on every attempt
        HttpRoleDefinitionSupplier resolver =
                new HttpRoleDefinitionSupplier(MAPPER, "http://127.0.0.1:1", 200, guard("resolve"));
        assertThatThrownBy(() -> resolver.lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
    }

    // --- U10: tag wrapper ---------------------------------------------------------------

    @Test // U10a — k<budget transient then 200 → recovers to the definitions
    void tag_transientThenRecovers() throws IOException {
        AtomicInteger n = new AtomicInteger();
        String defsBody =
                "[{\"key\":\"sensitivity\",\"valueType\":\"ENUM\",\"cardinality\":\"SINGLE\","
                        + "\"allowedValues\":[\"public\"],\"system\":true}]";
        String base = startServer(ex -> {
            int attempt = n.incrementAndGet();
            respond(ex, attempt < 2 ? 503 : 200, attempt < 2 ? "down" : defsBody);
        });

        List<TagDefinitionView> defs = tagClient(base).fetchApplicable("category", "c-1");

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).key()).isEqualTo("sensitivity");
        assertThat(n.get()).isEqualTo(2);
    }

    @Test // U10b — exhausting transient → TagDefinitionFetchException (→ 503) after all attempts
    void tag_exhaustedTransientThrows() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));

        assertThatThrownBy(() -> tagClient(base).fetchApplicable("category", "c-1"))
                .isInstanceOf(TagDefinitionFetchException.class);
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test // U10c — a 4xx → throws after EXACTLY ONE attempt (no retry)
    void tag_4xxThrowsImmediately() throws IOException {
        String base = startServer(ex -> respond(ex, 400, "bad request"));

        assertThatThrownBy(() -> tagClient(base).fetchApplicable("category", "c-1"))
                .isInstanceOf(TagDefinitionFetchException.class);
        assertThat(requests.get()).as("4xx is permanent — no retry").isEqualTo(1);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** A hand-advanced virtual clock — zero wall-clock dependence (ADR 0017 §Proof). */
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

        @Override
        public long millis() {
            return now.toEpochMilli();
        }
    }
}
