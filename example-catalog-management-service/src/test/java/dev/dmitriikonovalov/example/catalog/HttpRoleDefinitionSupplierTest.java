package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.HttpRoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpRoleDefinitionSupplier} against an in-process {@link HttpServer} stub — no
 * WireMock, mirroring {@code HttpOpaClientTest}. Covers H1–H4: the resolve round-trip, the no-match
 * (204) and every fail-closed path (500 / malformed / connection-refused), and the request URL shape.
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

    @Test // H1
    void resolvesRoundTrip() throws IOException {
        String base = startServer(ex -> respond(
                ex,
                200,
                "{\"code\":\"owner\",\"attributes\":{\"role_level\":40},"
                        + "\"permissions\":{\"catalog\":[\"read\",\"write\"]}}"));

        Optional<RoleDefinition> result = supplierFor(base).lookup("sub-1", "catalog", "c-1");
        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("owner");
        assertThat(result.get().permissions()).containsEntry("catalog", java.util.List.of("read", "write"));
    }

    @Test // H2
    void noMatch204IsEmpty() throws IOException {
        String base = startServer(ex -> respond(ex, 204, ""));
        assertThat(supplierFor(base).lookup("sub-1", "catalog", "c-1")).isEmpty();
    }

    @Test // H3a
    void failClosedOn500() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        assertThat(supplierFor(base).lookup("sub-1", "catalog", "c-1")).isEmpty();
    }

    @Test // H3b
    void failClosedOnMalformedBody() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not json {{{"));
        assertThat(supplierFor(base).lookup("sub-1", "catalog", "c-1")).isEmpty();
    }

    @Test // H3c
    void failClosedOnConnectionRefused() {
        // Nothing listening on this port → connect failure → empty.
        var supplier = supplierFor("http://127.0.0.1:1");
        assertThat(supplier.lookup("sub-1", "catalog", "c-1")).isEmpty();
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
