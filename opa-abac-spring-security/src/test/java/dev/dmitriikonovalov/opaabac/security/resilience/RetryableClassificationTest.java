package dev.dmitriikonovalov.opaabac.security.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.PolicyEngineException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * U1 — the shared retry classification (ADR 0017 §3). Transport failures are classified as exceptions;
 * HTTP-status failures as a status code. The retry set is {connection-refused, connect-timeout,
 * read-timeout, 5xx, 429}; the no-retry set is {4xx≠429, malformed/parse}.
 */
class RetryableClassificationTest {

    // --- transport exceptions: retryable ------------------------------------------------

    @Test // connection refused
    void connectionRefused_isRetryable() {
        assertThat(RetryableClassification.isRetryableError(new ConnectException("Connection refused")))
                .isTrue();
    }

    @Test // read/connect timeout — HttpTimeoutException (and its HttpConnectTimeoutException subclass) is
    // an IOException; request sent, no response. Safe to retry because the edges are read-only.
    void timeout_isRetryable() {
        assertThat(RetryableClassification.isRetryableError(new HttpTimeoutException("request timed out")))
                .isTrue();
    }

    @Test // a general IOException (reset, premature close)
    void generalIoException_isRetryable() {
        assertThat(RetryableClassification.isRetryableError(new IOException("connection reset")))
                .isTrue();
    }

    @Test // an IOException wrapped one layer down still classifies (the guard unwraps the cause chain)
    void wrappedIoException_isRetryable() {
        assertThat(RetryableClassification.isRetryableError(
                new RuntimeException("wrapper", new IOException("reset")))).isTrue();
    }

    // --- non-transport throws: NOT retryable --------------------------------------------

    @Test // a malformed-200 parse failure surfaced as a runtime exception is deterministic — no retry
    void parseFailure_isNotRetryable() {
        assertThat(RetryableClassification.isRetryableError(
                new IllegalStateException("malformed body"))).isFalse();
    }

    @Test // an unsafe-path / contract violation is permanent — no retry
    void illegalArgument_isNotRetryable() {
        assertThat(RetryableClassification.isRetryableError(
                new IllegalArgumentException("unsafe path"))).isFalse();
    }

    @Test // a null cause chain is handled, not retried
    void nullThrowable_isNotRetryable() {
        assertThat(RetryableClassification.isRetryableError(new RuntimeException("no cause"))).isFalse();
    }

    // --- HTTP status classification -----------------------------------------------------

    @ParameterizedTest // 5xx + 429 → retry
    @ValueSource(ints = {500, 502, 503, 504, 429})
    void transientStatuses_areRetryable(int status) {
        assertThat(RetryableClassification.retryableStatus(status)).isTrue();
    }

    @ParameterizedTest // every 4xx except 429 → fail fast; 3xx → not a failure
    @ValueSource(ints = {400, 401, 403, 404, 409, 422, 301, 302})
    void permanentOrNonFailureStatuses_areNotRetryable(int status) {
        assertThat(RetryableClassification.retryableStatus(status)).isFalse();
    }

    @ParameterizedTest // the boundary: 429 retries, 428/430 do not
    @CsvSource({"428,false", "429,true", "430,false", "499,false", "500,true", "599,true"})
    void statusBoundaries(int status, boolean expected) {
        assertThat(RetryableClassification.retryableStatus(status)).isEqualTo(expected);
    }

    // --- ENGINE-ERRORS U12: the OPA edge's PolicyEngineException is classified by KIND alone ----

    @Test
    void policyEngineException_transientKinds_areRetryable() {
        IOException io = new IOException("reset");
        assertThat(RetryableClassification.isRetryableError(PolicyEngineException.transport("t", io))).isTrue();
        assertThat(RetryableClassification.isRetryableError(
                PolicyEngineException.timeout("t", new HttpTimeoutException("slow")))).isTrue();
    }

    @ParameterizedTest // an HTTP_STATUS fault reuses the status table: 5xx and 429 retry, every 4xx does not
    @CsvSource({"500,true", "503,true", "429,true", "400,false", "404,false", "422,false"})
    void policyEngineException_httpStatus_followsTheStatusTable(int status, boolean expected) {
        assertThat(RetryableClassification.isRetryableError(PolicyEngineException.httpStatus(status, "t")))
                .isEqualTo(expected);
    }

    @Test // deterministic or self-inflicted kinds fail fast
    void policyEngineException_otherKinds_areNotRetryable() {
        assertThat(RetryableClassification.isRetryableError(PolicyEngineException.undefinedDecision("t"))).isFalse();
        assertThat(RetryableClassification.isRetryableError(
                PolicyEngineException.interrupted("t", new InterruptedException()))).isFalse();
        assertThat(RetryableClassification.isRetryableError(PolicyEngineException.circuitOpen("t", null))).isFalse();
    }

    @Test // KIND ONLY: a malformed body is not retried even when the parser's IOException is its cause
    void policyEngineException_malformedResponse_isNotRetried_evenWithAnIoCause() {
        PolicyEngineException malformed =
                PolicyEngineException.malformedResponse("bad body", new IOException("unexpected end-of-input"));

        assertThat(RetryableClassification.isRetryableError(malformed)).isFalse();
    }
}
