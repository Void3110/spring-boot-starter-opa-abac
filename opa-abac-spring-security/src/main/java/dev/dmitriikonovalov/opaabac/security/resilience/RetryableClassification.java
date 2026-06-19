package dev.dmitriikonovalov.opaabac.security.resilience;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.function.Predicate;

/**
 * The single source of the uniform retry classification (Slice B3, ADR 0017 §3) every edge shares. A
 * failure is <em>transient</em> (retry) or <em>permanent</em> (fail fast):
 *
 * <table border="1">
 *   <caption>Retry classification</caption>
 *   <tr><th>Failure</th><th>Retry?</th></tr>
 *   <tr><td>connection refused / connect timeout</td><td>✅ — server starting/restarting</td></tr>
 *   <tr><td>read timeout (request sent, no response)</td><td>✅ — safe; the edges are read-only</td></tr>
 *   <tr><td>5xx</td><td>✅ — transient server-side</td></tr>
 *   <tr><td>429</td><td>✅ — backpressure</td></tr>
 *   <tr><td>4xx (except 429)</td><td>❌ — permanent (a bug / contract violation)</td></tr>
 *   <tr><td>malformed-200 body (parse failure)</td><td>❌ — deterministic; the same bad body returns</td></tr>
 * </table>
 *
 * <p>Transport failures arrive as <em>exceptions</em> (classified by {@link #retryableError()}); HTTP-status
 * failures arrive as a returned response the edge inspects (classified by {@link #retryableStatus(int)},
 * which the edge folds into its own result predicate). Splitting the two mirrors how the JDK
 * {@code HttpClient} surfaces them — a 5xx is a normal response, only a transport fault throws.
 *
 * <h2>Side-effect-free invariant</h2>
 * Retrying any classified failure — <strong>including a read timeout</strong> — is safe only because all
 * three B3 edges are read-only (OPA decisions are server-side-stateless; resolve and tag are GETs). A
 * future edge that <em>mutates</em> state MUST NOT reuse this classification for retry (ADR 0017 §3).
 */
public final class RetryableClassification {

    private RetryableClassification() {}

    /**
     * The shared exception predicate: a transport-level failure is transient and retryable. Covers
     * connection-refused ({@link java.net.ConnectException}), connect timeout
     * ({@link java.net.http.HttpConnectTimeoutException}), read timeout ({@link HttpTimeoutException}), and
     * any other {@link IOException} (reset, premature close). A non-transport throw (e.g. an
     * {@link IllegalArgumentException} from an unsafe path, or a parse failure surfaced as a runtime
     * exception) is <em>not</em> retryable — it is deterministic and would just repeat.
     */
    public static Predicate<Throwable> retryableError() {
        return RetryableClassification::isRetryableError;
    }

    /**
     * Is this thrown failure a transient transport fault worth retrying? {@link IOException} and its
     * subclasses (connection refused, connect/read timeout, reset) → {@code true}; everything else →
     * {@code false}. Unwraps one layer of cause so a wrapper around an {@code IOException} still classifies.
     */
    public static boolean isRetryableError(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                // ConnectException, HttpConnectTimeoutException, HttpTimeoutException all extend IOException.
                return true;
            }
            if (cause == cause.getCause()) {
                break; // guard a self-referential cause chain
            }
        }
        return false;
    }

    /**
     * Is this HTTP status a transient failure worth retrying? {@code 5xx} and {@code 429} → {@code true};
     * every {@code 4xx} except {@code 429} → {@code false} (permanent); {@code 2xx}/{@code 3xx} are not
     * failures and are never classified here. An edge whose own success signal is a specific 2xx
     * (e.g. 200/204) folds {@code !isSuccess && retryableStatus(status)} into its result predicate.
     */
    public static boolean retryableStatus(int status) {
        if (status == 429) {
            return true;
        }
        return status >= 500 && status <= 599;
    }
}
