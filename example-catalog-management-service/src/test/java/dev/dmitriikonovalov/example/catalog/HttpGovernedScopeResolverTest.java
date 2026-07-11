package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.HttpGovernedScopeResolver;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests for {@link HttpGovernedScopeResolver} against an in-process {@link HttpServer} stub — no
 * WireMock, mirroring {@code HttpRoleDefinitionSupplierTest} (Slice B4 QA U1–U4).
 *
 * <p>The resolver returns a {@link Specification} (never an {@code Optional}), so each test invokes the
 * returned spec against a <em>mocked</em> {@link CriteriaBuilder}/{@link Root} and asserts which predicate
 * it builds: an {@code id IN (ids)} (the governed path) vs {@code cb.disjunction()} (the fail-closed
 * deny-all). The whole point is that <b>every</b> non-affirmative outcome — empty array, 5xx, timeout,
 * connection-refused, malformed body — lands on deny-all <b>without throwing</b> (a throw would 500 the
 * list instead of emptying it). U3 is the keystone.
 */
class HttpGovernedScopeResolverTest {

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

    private HttpGovernedScopeResolver resolverFor(String baseUrl) {
        return new HttpGovernedScopeResolver(MAPPER, baseUrl, 500);
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

    // --- Criteria probe: invoke the spec and capture which predicate it asks the builder for ----------

    /** What predicate did the returned Specification build? */
    private enum Built {
        IN, // root.get("id").in(...)
        DISJUNCTION // cb.disjunction() — the deny-all floor
    }

    /**
     * Invoke {@code spec.toPredicate(...)} against a mocked criteria API and report which call it made.
     * {@code in(...)} (the governed path) and {@code disjunction()} (deny-all) are mutually exclusive, so
     * one probe distinguishes the two outcomes without a database.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Built probe(Specification spec, AtomicReference<Object> inArg) {
        Root<Object> root = mock(Root.class);
        Path<Object> idPath = mock(Path.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);

        Predicate inPredicate = mock(Predicate.class);
        Predicate disjunction = mock(Predicate.class);
        when(root.get("id")).thenReturn(idPath);
        // The resolver calls Path#in(Collection) with a List<UUID>; stub that overload and capture the arg.
        when(idPath.in(any(java.util.Collection.class))).thenAnswer(inv -> {
            inArg.set(inv.getArgument(0));
            return inPredicate;
        });
        when(cb.disjunction()).thenReturn(disjunction);

        Predicate result = spec.toPredicate(root, query, cb);
        if (result == disjunction) {
            return Built.DISJUNCTION;
        }
        if (result == inPredicate) {
            return Built.IN;
        }
        throw new AssertionError("spec built an unexpected predicate");
    }

    @SuppressWarnings("rawtypes")
    private static Built probe(Specification spec) {
        return probe(spec, new AtomicReference<>());
    }

    // --- U1 — governed ids → id IN (ids) -------------------------------------------------------------

    @Test
    void buildsInPredicateFromGovernedIds() throws IOException {
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String base = startServer(ex -> respond(ex, 200, "[\"" + id1 + "\",\"" + id2 + "\"]"));

        AtomicReference<Object> inArg = new AtomicReference<>();
        Built built = probe(resolverFor(base).governedScope("sub-1", "catalog"), inArg);

        assertThat(built).isEqualTo(Built.IN);
        @SuppressWarnings("unchecked")
        Iterable<UUID> capturedIds = (Iterable<UUID>) inArg.get();
        assertThat(capturedIds).containsExactly(id1, id2);
    }

    // --- U2 — authoritative empty array → deny-all ---------------------------------------------------

    @Test
    void emptyArrayIsDenyAll() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "[]"));
        assertThat(probe(resolverFor(base).governedScope("sub-1", "catalog"))).isEqualTo(Built.DISJUNCTION);
    }

    // --- U3 — outage (5xx / timeout / connection-refused) → deny-all, NEVER throws (the keystone) -----

    @Test
    void serverError5xxIsDenyAll_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        assertThat(probe(resolverFor(base).governedScope("sub-1", "catalog"))).isEqualTo(Built.DISJUNCTION);
    }

    @Test
    void notFound404IsDenyAll_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 404, "nope"));
        assertThat(probe(resolverFor(base).governedScope("sub-1", "catalog"))).isEqualTo(Built.DISJUNCTION);
    }

    @Test
    void connectionRefusedIsDenyAll_noThrow() {
        // Nothing listening on :1 → transport failure → deny-all, no throw.
        assertThat(probe(resolverFor("http://127.0.0.1:1").governedScope("sub-1", "catalog")))
                .isEqualTo(Built.DISJUNCTION);
    }

    @Test
    void timeoutIsDenyAll_noThrow() throws IOException {
        String base = startServer(ex -> {
            try {
                Thread.sleep(2000); // past the 500ms request timeout
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "[]");
        });
        assertThat(probe(resolverFor(base).governedScope("sub-1", "catalog"))).isEqualTo(Built.DISJUNCTION);
    }

    // --- U4 — malformed body / non-UUID element → deny-all, no throw ---------------------------------

    @Test
    void malformedBodyIsDenyAll_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not json {{{"));
        assertThat(probe(resolverFor(base).governedScope("sub-1", "catalog"))).isEqualTo(Built.DISJUNCTION);
    }

    @Test
    void nonUuidElementIsDenyAll_noThrow() throws IOException {
        // A single bad element discards the whole result (never a partial-parse widening).
        String base = startServer(ex -> respond(
                ex, 200, "[\"11111111-1111-1111-1111-111111111111\",\"not-a-uuid\"]"));
        assertThat(probe(resolverFor(base).governedScope("sub-1", "catalog"))).isEqualTo(Built.DISJUNCTION);
    }

    @Test
    void blankBodyIsDenyAll_noThrow() throws IOException {
        String base = startServer(ex -> respond(ex, 200, ""));
        assertThat(probe(resolverFor(base).governedScope("sub-1", "catalog"))).isEqualTo(Built.DISJUNCTION);
    }

    // --- null / blank coordinates → deny-all, no call made ------------------------------------------

    @Test
    void nullSubjectIsDenyAll_noCall() throws IOException {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        String base = startServer(ex -> {
            called.set(true);
            respond(ex, 200, "[\"11111111-1111-1111-1111-111111111111\"]");
        });
        var resolver = resolverFor(base);
        assertThat(probe(resolver.governedScope(null, "catalog"))).isEqualTo(Built.DISJUNCTION);
        assertThat(probe(resolver.governedScope("  ", "catalog"))).isEqualTo(Built.DISJUNCTION);
        assertThat(called.get()).isFalse();
    }

    // --- request shape -------------------------------------------------------------------------------

    @Test
    void sendsCorrectRequestUrl() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        String base = startServer(ex -> {
            path.set(ex.getRequestURI().getPath());
            query.set(ex.getRequestURI().getQuery());
            respond(ex, 200, "[]");
        });

        resolverFor(base).governedScope("sub-42", "catalog");

        assertThat(path.get()).isEqualTo("/internal/governed-targets");
        assertThat(query.get()).contains("subject=sub-42").contains("resourceType=catalog");
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
