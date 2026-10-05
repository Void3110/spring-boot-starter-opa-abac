package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * ENGINE-ERRORS U38 — a policy that itself fails on this input (OPA's policy-local {@code eval_*} errors, answered
 * by the data API as HTTP 500 — measured on OPA 1.10.1) is {@link PolicyEngineException.Kind#EVALUATION_ERROR} on
 * all three methods, not a retryable {@code HTTP_STATUS}: treated as deterministic for the input, so it is neither
 * retried nor counted on the shared breaker. OPA's operational {@code eval_*} codes stay a status. Only the codes
 * are read — never the messages or locations.
 */
class HttpOpaClientEvaluationErrorTest {

    /** The body OPA 1.10.1 answers for a complete rule producing two outputs, its location kept as sent. */
    private static final String CONFLICT_BODY = """
            {"code":"internal_error","message":"error(s) occurred while evaluating query",
             "errors":[{"code":"eval_conflict_error","message":"complete rules must not produce multiple outputs",
                        "location":{"file":"/srv/policies/product.rego","row":42,"col":1}}]}""";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpOpaClient clientAnswering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        OpaClientConfig config = new OpaClientConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(500), "allow");
        return new HttpOpaClient(new ObjectMapper(), new PerTypePolicyPathResolver("catalog"), config);
    }

    private static AbacContext ctx() {
        return new AbacContext(
                new AbacContext.Subject("u", List.of(), Map.of()),
                "product:read",
                new AbacContext.Resource("product", "p-1", Map.of()),
                Map.of());
    }

    private static PolicyEngineException thrownBy(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(PolicyEngineException.class);
        return (PolicyEngineException) thrown;
    }

    @Test
    void evalErrorBody_isEvaluationError_onEveryMethod() throws IOException {
        HttpOpaClient client = clientAnswering(500, CONFLICT_BODY);

        List<ThrowingCallable> calls = List.of(
                () -> client.decide(ctx()), () -> client.compile(ctx()), () -> client.allowAll(List.of(ctx())));
        for (ThrowingCallable call : calls) {
            PolicyEngineException e = thrownBy(call);
            assertThat(e.kind()).isEqualTo(PolicyEngineException.Kind.EVALUATION_ERROR);
            assertThat(e.httpStatus()).isEmpty();
        }
    }

    @Test // the three policy-local codes (OPA 1.10.1 defines eight eval_* codes in all)
    void policyLocalCodes_areEvaluationErrors() throws IOException {
        for (String code : List.of("eval_conflict_error", "eval_type_error", "eval_with_merge_error")) {
            HttpOpaClient client = clientAnswering(500, "{\"code\":\"internal_error\",\"errors\":[{\"code\":\""
                    + code + "\"}]}");
            assertThat(thrownBy(() -> client.decide(ctx())).kind()).as(code)
                    .isEqualTo(PolicyEngineException.Kind.EVALUATION_ERROR);
            server.stop(0);
        }
    }

    @Test // the operational and environmental eval_* codes are not the policy's own failure: a retryable status
    void operationalEvalCodes_stayHttpStatus() {
        for (String code : List.of("eval_cancel_error", "eval_internal_error", "eval_builtin_error",
                "eval_http_send_network_error", "eval_http_send_internal_error")) {
            String body = "{\"code\":\"internal_error\",\"errors\":[{\"code\":\"" + code + "\"}]}";
            PolicyEngineException e = thrownBy(() -> clientAnswering(500, body).decide(ctx()));
            assertThat(e.kind()).as(code).isEqualTo(PolicyEngineException.Kind.HTTP_STATUS);
            assertThat(e.httpStatus()).as(code).hasValue(500);
            server.stop(0);
        }
    }

    @Test // the message names the code — never the error's message or the policy file's location
    void message_namesTheCode_notTheMessageOrLocation() {
        PolicyEngineException e = thrownBy(() -> clientAnswering(500, CONFLICT_BODY).decide(ctx()));

        assertThat(e.getMessage()).contains("policy evaluation error eval_conflict_error")
                .doesNotContain("/srv/policies", "multiple outputs");
    }

    @Test // anything that is not an all-eval_* error body stays a 500 status — retryable like any other 5xx
    void otherFiveHundreds_stayHttpStatus() {
        List<String> bodies = List.of(
                "not-json",
                "{}",
                "{\"code\":\"internal_error\",\"errors\":[]}",
                "{\"code\":\"internal_error\",\"errors\":"
                        + "[{\"code\":\"eval_conflict_error\"},{\"code\":\"storage_error\"}]}",
                "{\"code\":\"internal_error\",\"errors\":[{\"code\":\"eval_Injected text here\"}]}",
                "{\"code\":\"internal_error\",\"errors\":[{\"message\":\"no code\"}]}");
        for (String body : bodies) {
            PolicyEngineException e = thrownBy(() -> clientAnswering(500, body).decide(ctx()));
            assertThat(e.kind()).as(body).isEqualTo(PolicyEngineException.Kind.HTTP_STATUS);
            assertThat(e.httpStatus()).as(body).hasValue(500);
            server.stop(0);
        }
    }

    @Test // only a 500 is read: an eval_* body on another status is still that status
    void evalBodyOnAnotherStatus_staysHttpStatus() {
        PolicyEngineException e = thrownBy(() -> clientAnswering(503, CONFLICT_BODY).decide(ctx()));

        assertThat(e.kind()).isEqualTo(PolicyEngineException.Kind.HTTP_STATUS);
        assertThat(e.httpStatus()).hasValue(503);
    }
}
