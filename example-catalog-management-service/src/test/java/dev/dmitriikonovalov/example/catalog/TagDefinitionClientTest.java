package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionClient;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionFetchException;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TagDefinitionClient} against an in-process {@link HttpServer} stub — no WireMock,
 * mirroring {@code HttpRoleDefinitionSupplierTest}. Covers H1 (the round-trip + request URL) and H2 (every
 * fail-closed path → {@link TagDefinitionFetchException}, never an empty "all-allowed" set).
 */
class TagDefinitionClientTest {

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

    private TagDefinitionClient clientFor(String baseUrl) {
        return new TagDefinitionClient(MAPPER, baseUrl, 500);
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
    void parsesApplicableDefinitionsAndSendsCorrectUrl() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        String base = startServer(ex -> {
            path.set(ex.getRequestURI().getPath());
            query.set(ex.getRequestURI().getQuery());
            respond(ex, 200,
                    "[{\"key\":\"sensitivity\",\"valueType\":\"ENUM\",\"cardinality\":\"SINGLE\","
                            + "\"allowedValues\":[\"public\",\"internal\"],\"system\":true},"
                            + "{\"key\":\"region\",\"valueType\":\"ENUM\",\"cardinality\":\"MULTI\","
                            + "\"allowedValues\":[\"emea\",\"amer\"]}]");
        });

        List<TagDefinitionView> defs = clientFor(base).fetchApplicable("category", "c-1");

        assertThat(defs).hasSize(2);
        assertThat(defs).anyMatch(d -> d.key().equals("sensitivity") && !d.isMulti() && d.isEnum());
        assertThat(defs).anyMatch(d -> d.key().equals("region") && d.isMulti());
        assertThat(path.get()).isEqualTo("/internal/tag-definitions");
        assertThat(query.get())
                .contains("resourceType=category")
                .contains("resourceId=c-1");
    }

    @Test // H2a
    void failClosedOn500() throws IOException {
        String base = startServer(ex -> respond(ex, 500, "boom"));
        assertThatThrownBy(() -> clientFor(base).fetchApplicable("category", "c-1"))
                .isInstanceOf(TagDefinitionFetchException.class);
    }

    @Test // H2b
    void failClosedOnMalformedBody() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "not json {{{"));
        assertThatThrownBy(() -> clientFor(base).fetchApplicable("category", "c-1"))
                .isInstanceOf(TagDefinitionFetchException.class);
    }

    @Test // H2c
    void failClosedOnConnectionRefused() {
        var client = clientFor("http://127.0.0.1:1");
        assertThatThrownBy(() -> client.fetchApplicable("category", "c-1"))
                .isInstanceOf(TagDefinitionFetchException.class);
    }

    @Test // H2d
    void failClosedOnEmptyBody() throws IOException {
        String base = startServer(ex -> respond(ex, 200, ""));
        assertThatThrownBy(() -> clientFor(base).fetchApplicable("category", "c-1"))
                .isInstanceOf(TagDefinitionFetchException.class);
    }

    @FunctionalInterface
    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
