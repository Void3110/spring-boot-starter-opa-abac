package dev.dmitriikonovalov.opaabac.keycloak.directory;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.opaabac.security.directory.DirectoryUser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit + integration tests for {@link KeycloakUserDirectory} (Slice-2 QA U2a/U2b + I2a–I2c) against an
 * in-process {@link HttpServer} stub standing in for the Keycloak admin API — no WireMock. The keystones:
 * U2b (blank query → zero HTTP calls, the no-enumeration rule), I2b (every error edge → empty with a
 * distinct WARN, never a throw — the fail-closed / no-oracle proof), U2a (the limit clamp is enforced by
 * the implementation on both the request and the response).
 */
class KeycloakUserDirectoryTest {

    private static final String REALM = "catalog-demo";
    private static final String TOKEN_OK =
            "{\"access_token\":\"stub-token\",\"expires_in\":300,\"token_type\":\"Bearer\"}";

    private HttpServer server;
    private KeycloakUserDirectory directory;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger searchCalls = new AtomicInteger();
    private final AtomicReference<String> lastSearchQuery = new AtomicReference<>();
    private ListAppender<ILoggingEvent> warnings;

    @BeforeEach
    void captureLogs() {
        warnings = new ListAppender<>();
        warnings.start();
        ((Logger) LoggerFactory.getLogger(KeycloakUserDirectory.class)).addAppender(warnings);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(KeycloakUserDirectory.class)).detachAppender(warnings);
        if (directory != null) {
            directory.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    /** A stub answering the token grant with {@code tokenStatus} and the users search with the handler. */
    private String startStub(int tokenStatus, UsersHandler usersHandler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/" + REALM + "/protocol/openid-connect/token", exchange -> {
            tokenCalls.incrementAndGet();
            try {
                respondJson(exchange, tokenStatus, tokenStatus == 200 ? TOKEN_OK : "{\"error\":\"unauthorized_client\"}");
            } finally {
                exchange.close();
            }
        });
        server.createContext("/admin/realms/" + REALM + "/users", exchange -> {
            searchCalls.incrementAndGet();
            lastSearchQuery.set(exchange.getRequestURI().getQuery());
            try {
                usersHandler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private KeycloakUserDirectory directory(String serverUrl) {
        KeycloakDirectoryProperties props = new KeycloakDirectoryProperties();
        props.setServerUrl(serverUrl);
        props.setRealm(REALM);
        props.setClientId("catalog-directory");
        props.setClientSecret("stub-secret");
        directory = new KeycloakUserDirectory(props);
        return directory;
    }

    private static String user(String id, String username) {
        return "{\"id\":\"" + id + "\"" + (username == null ? "" : ",\"username\":\"" + username + "\"") + "}";
    }

    // --- U2a: the limit clamp, enforced by the implementation ------------------------------------

    @Test
    void clampsLimitOnTheOutgoingRequest() throws IOException {
        String url = startStub(200, exchange -> respondJson(exchange, 200, "[]"));
        KeycloakUserDirectory dir = directory(url);

        dir.search("al", -1);
        assertThat(lastSearchQuery.get()).contains("max=20"); // <=0 -> default 20

        dir.search("al", 1000);
        assertThat(lastSearchQuery.get()).contains("max=50"); // >50 -> hard max 50

        dir.search("al", 10);
        assertThat(lastSearchQuery.get()).contains("max=10"); // in range -> as asked
    }

    @Test
    void reEnforcesTheClampOnAMisbehavingServersResponse() throws IOException {
        StringBuilder sixty = new StringBuilder("[");
        for (int i = 0; i < 60; i++) {
            sixty.append(i > 0 ? "," : "").append(user("id-" + i, "user-" + i));
        }
        String url = startStub(200, exchange -> respondJson(exchange, 200, sixty.append("]").toString()));

        List<DirectoryUser> result = directory(url).search("user", 1000);

        assertThat(result).hasSize(50); // the server ignored max=50; the impl still bounds the result
    }

    // --- U2b: blank query never contacts Keycloak ------------------------------------------------

    @Test
    void blankQueryReturnsEmptyWithoutAnyKeycloakCall() throws IOException {
        String url = startStub(200, exchange -> respondJson(exchange, 200, "[]"));
        KeycloakUserDirectory dir = directory(url);

        assertThat(dir.search("   ", 10)).isEmpty();
        assertThat(dir.search("", 10)).isEmpty();
        assertThat(dir.search(null, 10)).isEmpty();

        assertThat(tokenCalls.get()).isZero();
        assertThat(searchCalls.get()).isZero();
    }

    // --- I2a: the happy path — matching users mapped and bounded ---------------------------------

    @Test
    void mapsMatchingUsersToDirectoryUsers() throws IOException {
        String url = startStub(200, exchange ->
                respondJson(exchange, 200, "[" + user("id-1", "alice") + "," + user("id-2", "albert") + "]"));

        List<DirectoryUser> result = directory(url).search("al", 10);

        assertThat(result).containsExactly(
                new DirectoryUser("id-1", "alice"),
                new DirectoryUser("id-2", "albert"));
        assertThat(lastSearchQuery.get()).contains("search=al").contains("max=10");
    }

    // --- I2b: every error edge -> empty, never a throw, distinct WARNs ---------------------------

    @Test
    void searchErrorReturnsEmptyNeverThrows() throws IOException {
        String url = startStub(200, exchange -> respondJson(exchange, 500, "{\"error\":\"boom\"}"));

        assertThat(directory(url).search("al", 10)).isEmpty();

        assertThat(warnMessages()).singleElement().asString().contains("search failed");
    }

    @Test
    void connectionRefusedReturnsEmptyNeverThrows() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        assertThat(directory("http://127.0.0.1:" + deadPort).search("al", 10)).isEmpty();

        assertThat(warnMessages()).hasSize(1);
    }

    @Test
    void tokenGrantFailureReturnsEmptyWithDistinctWarn() throws IOException {
        String url = startStub(401, exchange -> respondJson(exchange, 200, "[]"));

        assertThat(directory(url).search("al", 10)).isEmpty();

        assertThat(searchCalls.get()).isZero(); // the grant failed; the users endpoint was never reached
        // The token-grant WARN is DISTINCT from the generic search-failure WARN (I2b).
        assertThat(warnMessages()).singleElement().asString().contains("token grant");
    }

    // --- I2c: a blank/null username stays renderable ----------------------------------------------

    @Test
    void blankUsernameFallsBackToSubject() throws IOException {
        String url = startStub(200, exchange ->
                respondJson(exchange, 200, "[" + user("id-3", null) + "," + user("id-4", " ") + "]"));

        List<DirectoryUser> result = directory(url).search("x", 10);

        assertThat(result).containsExactly(
                new DirectoryUser("id-3", "id-3"),
                new DirectoryUser("id-4", "id-4"));
    }

    private List<String> warnMessages() {
        return warnings.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @FunctionalInterface
    private interface UsersHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
