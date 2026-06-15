package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.HttpRoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpRoleDefinitionSupplier} against an in-process {@link HttpServer} stub — no
 * WireMock, mirroring {@code HttpOpaClientTest}.
 *
 * <p>B2 strict classification (QA U7–U12): ONLY {@code 204} → {@code Optional.empty()} (authoritative
 * no-role), ONLY {@code 200}+valid body → resolved; everything else — {@code 200}-blank, every 4xx,
 * every 5xx, timeout, connection-refused, malformed-{@code 200} — <b>throws</b>
 * {@link RoleResolutionException} (outage → deny). The WARN carries status/class only, never PII.
 */
class HttpRoleDefinitionSupplierTest {

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

    private HttpRoleDefinitionSupplier supplierFor(String baseUrl) {
        return new HttpRoleDefinitionSupplier(MAPPER, baseUrl, 500);
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

    @Test // H1 / U7 — 200 + valid body → resolved
    void resolvesRoundTrip() throws IOException {
        String base = startServer(ex -> respond(
                ex,
                200,
                "{\"code\":\"owner\",\"attributes\":{\"role_level\":40},"
                        + "\"permissions\":{\"catalog\":[\"READ\",\"WRITE\"]}}"));

        Optional<RoleDefinition> result = supplierFor(base).lookup("sub-1", "catalog", "c-1");
        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("owner");
        assertThat(result.get().permissions()).containsEntry("catalog", java.util.List.of("READ", "WRITE"));
    }

    @Test // H2 / U8 — 204 → authoritative no-role (Optional.empty(), the realm fallback may decide)
    void noMatch204IsEmpty() throws IOException {
        String base = startServer(ex -> respond(ex, 204, ""));
        assertThat(supplierFor(base).lookup("sub-1", "catalog", "c-1")).isEmpty();
    }

    @Test // U9 — 200 + blank body → throws (a contract-violating 200 is untrustworthy, not no-role)
    void blank200Throws() throws IOException {
        String base = startServer(ex -> respond(ex, 200, ""));
        assertThatThrownBy(() -> supplierFor(base).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
    }

    @Test // U10 — 500 (and 503) → throws (outage, never no-role)
    void serverError5xxThrows() throws IOException {
        String base500 = startServer(ex -> respond(ex, 500, "boom"));
        assertThatThrownBy(() -> supplierFor(base500).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
        server.stop(0);

        String base503 = startServer(ex -> respond(ex, 503, "unavailable"));
        assertThatThrownBy(() -> supplierFor(base503).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
    }

    @Test // U11 — 404 (and 400) → throws — no 4xx maps to no-role (only 204 is the no-role signal)
    void clientError4xxThrows() throws IOException {
        String base404 = startServer(ex -> respond(ex, 404, "not found"));
        assertThatThrownBy(() -> supplierFor(base404).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
        server.stop(0);

        String base400 = startServer(ex -> respond(ex, 400, "bad request"));
        assertThatThrownBy(() -> supplierFor(base400).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
    }

    @Test // U12a — malformed 200 body (bad JSON) → throws
    void malformed200BodyThrows() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not json {{{"));
        assertThatThrownBy(() -> supplierFor(base).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
    }

    @Test // U12b — connection refused (nothing listening) → throws
    void connectionRefusedThrows() {
        var supplier = supplierFor("http://127.0.0.1:1");
        assertThatThrownBy(() -> supplier.lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
    }

    @Test // U12c — a slow handler past the request timeout → throws (timeout = outage)
    void timeoutThrows() throws IOException {
        // The supplier's request timeout is 500ms (supplierFor); the handler sleeps well past it.
        String base = startServer(ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 204, "");
        });
        assertThatThrownBy(() -> supplierFor(base).lookup("sub-1", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
    }

    @Test // U12 — the WARN/exception path carries NO PII: not the userId, token, or body. We assert the
    // thrown message + cause never contain the (distinctive) userId or the response body.
    void outageThrow_carriesNoPii() throws IOException {
        String secretUser = "sub-SECRET-9f3a";
        String secretBody = "TOP-SECRET-BODY-PAYLOAD";
        String base = startServer(ex -> respond(ex, 500, secretBody));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> supplierFor(base).lookup(secretUser, "catalog", "c-1"));

        assertThat(thrown).isInstanceOf(RoleResolutionException.class);
        assertThat(thrown.getMessage()).doesNotContain(secretUser).doesNotContain(secretBody);
        // The cause (if any) is an HTTP/transport exception, not one carrying the body or the userId.
        if (thrown.getCause() != null) {
            assertThat(String.valueOf(thrown.getCause().getMessage()))
                    .doesNotContain(secretUser)
                    .doesNotContain(secretBody);
        }
    }

    @Test // H4
    void sendsCorrectRequestUrl() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        String base = startServer(ex -> {
            path.set(ex.getRequestURI().getPath());
            query.set(ex.getRequestURI().getQuery());
            respond(ex, 204, "");
        });

        supplierFor(base).lookup("sub-42", "catalog", "c-99");

        assertThat(path.get()).isEqualTo("/internal/effective-role");
        assertThat(query.get())
                .contains("userId=sub-42")
                .contains("resourceType=catalog")
                .contains("resourceId=c-99");
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
