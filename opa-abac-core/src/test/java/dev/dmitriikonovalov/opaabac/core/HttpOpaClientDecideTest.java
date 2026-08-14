package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * U12–U14 — the additive decision envelope (ADR 0030 §6). Against an in-process {@link HttpServer} stub,
 * no WireMock.
 *
 * <p>The theme of every case: {@code decide} sees exactly what {@code allow} sees plus an optional
 * reason, and <strong>a reason is never invented and never widening</strong>. Each case asserts the
 * {@code allow} half too, which is the additivity proof at the call level — the boolean contract is
 * unchanged on all four wire shapes.
 */
class HttpOpaClientDecideTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static AbacContext context() {
        return new AbacContext(
                new AbacContext.Subject("sup-anna", List.of(), Map.of("acr", "aal1")),
                "category:view",
                new AbacContext.Resource("category", "k-1", Map.of()),
                Map.of());
    }

    /** Start a stub answering every request with the given body and status. */
    private OpaClient clientReturning(int status, String body) throws IOException {
        return clientFor(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        });
    }

    private OpaClient clientFor(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        OpaClientConfig config = new OpaClientConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(500), "allow");
        return new HttpOpaClient(new ObjectMapper(), new PerTypePolicyPathResolver(""), config);
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    // --- U13: the four wire shapes ------------------------------------------------------

    @Test // deny + a complete reason -> the typed reason, and allow() still false
    void decide_parsesACompleteDenyReason() throws IOException {
        OpaClient client = clientReturning(200, """
                {"result":{"allow":false,"deny_reason":
                  {"type":"insufficient_user_authentication","required_acr":"aal2","max_age":300}}}""");

        OpaDecision decision = client.decide(context());

        assertThat(decision.allow()).isFalse();
        assertThat(decision.denyReason())
                .isEqualTo(new DenyReason("insufficient_user_authentication", "aal2", 300));
        assertThat(decision.hasCompleteReason()).isTrue();
        assertThat(client.allow(context())).isFalse();
    }

    @Test // a plain deny -> no reason
    void decide_plainDenyCarriesNoReason() throws IOException {
        OpaClient client = clientReturning(200, "{\"result\":{\"allow\":false}}");

        OpaDecision decision = client.decide(context());

        assertThat(decision.allow()).isFalse();
        assertThat(decision.denyReason()).isNull();
        assertThat(client.allow(context())).isFalse();
    }

    @Test // a plain allow -> allow, no reason
    void decide_allowCarriesNoReason() throws IOException {
        OpaClient client = clientReturning(200, "{\"result\":{\"allow\":true}}");

        OpaDecision decision = client.decide(context());

        assertThat(decision.allow()).isTrue();
        assertThat(decision.denyReason()).isNull();
        assertThat(client.allow(context())).isTrue();
    }

    @Test // a CONTRADICTORY document (allow + a reason) -> the allow wins, the reason is dropped
    void decide_dropsAReasonThatAccompaniesAnAllow() throws IOException {
        OpaClient client = clientReturning(200, """
                {"result":{"allow":true,"deny_reason":
                  {"type":"insufficient_user_authentication","required_acr":"aal2","max_age":300}}}""");

        OpaDecision decision = client.decide(context());

        assertThat(decision.allow()).isTrue();
        assertThat(decision.denyReason()).isNull();
        assertThat(client.allow(context())).isTrue();
    }

    // --- U14: a malformed reason drops, never throws, never widens ----------------------

    @Test // wrong types, missing fields, and a non-object shape all drop the reason and keep the deny
    void decide_dropsEveryMalformedReasonShape() throws IOException {
        List<String> malformed = List.of(
                // max_age as a string: Jackson would happily coerce it; the library will not guess
                """
                {"result":{"allow":false,"deny_reason":
                  {"type":"insufficient_user_authentication","required_acr":"aal2","max_age":"300"}}}""",
                // required_acr as a number
                """
                {"result":{"allow":false,"deny_reason":
                  {"type":"insufficient_user_authentication","required_acr":2,"max_age":300}}}""",
                // max_age missing
                """
                {"result":{"allow":false,"deny_reason":
                  {"type":"insufficient_user_authentication","required_acr":"aal2"}}}""",
                // type missing
                "{\"result\":{\"allow\":false,\"deny_reason\":{\"required_acr\":\"aal2\",\"max_age\":300}}}",
                // an empty reason object
                "{\"result\":{\"allow\":false,\"deny_reason\":{}}}",
                // not an object at all
                "{\"result\":{\"allow\":false,\"deny_reason\":\"insufficient_user_authentication\"}}",
                "{\"result\":{\"allow\":false,\"deny_reason\":[1,2,3]}}",
                // a float window is not a window this library will advertise
                """
                {"result":{"allow":false,"deny_reason":
                  {"type":"insufficient_user_authentication","required_acr":"aal2","max_age":300.5}}}""");

        for (String body : malformed) {
            tearDown();
            OpaClient client = clientReturning(200, body);
            OpaDecision decision = client.decide(context());
            assertThat(decision.allow()).as("body: %s", body).isFalse();
            assertThat(decision.denyReason()).as("body: %s", body).isNull();
        }
    }

    @Test // unknown fields inside a well-formed reason are tolerated, like every other envelope field
    void decide_toleratesUnknownFieldsInsideTheReason() throws IOException {
        OpaClient client = clientReturning(200, """
                {"result":{"allow":false,"granted":true,"deny_reason":
                  {"type":"insufficient_user_authentication","required_acr":"aal2","max_age":300,
                   "future_field":"ignored"}}}""");

        assertThat(client.decide(context()).denyReason())
                .isEqualTo(new DenyReason("insufficient_user_authentication", "aal2", 300));
    }

    @Test // transport/5xx/non-JSON: the existing fail-closed false, with a null reason, and no throw
    void decide_failsClosedWithoutAReason() throws IOException {
        OpaClient nonJson = clientReturning(200, "not json at all");
        assertThatCode(() -> assertThat(nonJson.decide(context())).isEqualTo(OpaDecision.deny()))
                .doesNotThrowAnyException();

        tearDown();
        OpaClient serverError = clientReturning(503, "{\"result\":{\"allow\":true}}");
        assertThat(serverError.decide(context())).isEqualTo(OpaDecision.deny());

        tearDown();
        OpaClient missingField = clientReturning(200, "{\"result\":{}}");
        assertThat(missingField.decide(context())).isEqualTo(OpaDecision.deny());

        // A dead endpoint: nothing listening on the port the client was built for.
        OpaClientConfig config = new OpaClientConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(200), "allow");
        OpaClient dead = new HttpOpaClient(new ObjectMapper(), new PerTypePolicyPathResolver(""), config);
        server.stop(0);
        server = null;
        assertThat(dead.decide(context())).isEqualTo(OpaDecision.deny());
    }

    @Test // the reason travels on the SAME response — decide() must not cost an extra round-trip
    void decide_makesExactlyOneCall() throws IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        OpaClient client = clientFor(exchange -> {
            calls.incrementAndGet();
            byte[] bytes = ("""
                    {"result":{"allow":false,"deny_reason":
                      {"type":"insufficient_user_authentication","required_acr":"aal2","max_age":300}}}""")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        client.decide(context());

        assertThat(calls.get()).isEqualTo(1);
    }
}
