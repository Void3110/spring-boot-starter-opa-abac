package dev.dmitriikonovalov.example.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.resilience.CallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.CallNotPermittedException;
import dev.dmitriikonovalov.opaabac.security.resilience.RetryableClassification;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The Phase-4 swap: a {@link RoleDefinitionSupplier} that resolves the caller's effective role by
 * calling the user-management service's internal resolve API
 * ({@code GET <base>/internal/effective-role?userId&resourceType&resourceId}). This is <b>app code</b>,
 * not a library change — the library SPI was built for exactly this single-bean swap; the demo supplier
 * stays available behind {@code catalog.role-source=demo} (the default).
 *
 * <h2>Failure posture — fail-closed by strict classification (B2)</h2>
 * The resolve result is <strong>tri-state</strong> (the {@link RoleDefinitionSupplier} contract):
 * <ul>
 *   <li><b>{@code 200} + a valid body</b> → {@code Optional.of(role)} (resolved).</li>
 *   <li><b>{@code 204}</b> → {@link Optional#empty()} — the <em>authoritative</em> no-role signal; the
 *       catalog policies' JWT realm-role fallback ({@code catalog-viewer} → READ, {@code catalog-editor}
 *       → READ+WRITE+TAG) decides for non-members / type-level creates, as designed.</li>
 *   <li><b>everything else</b> → <strong>throws {@link RoleResolutionException}</strong> (an outage):
 *       {@code 200} with a blank body, every 4xx, every 5xx, a timeout, a connection failure, a parse
 *       failure. The role is then <em>unknown</em>, so the caller fails closed (the gate denies) and the
 *       realm fallback is <strong>never</strong> reached — closing the one widening-on-failure path
 *       (review C1/C4, ADR 0014): an outage can no longer ride the fallback to a grant wider than the
 *       subject's resolved role.</li>
 * </ul>
 * Only {@code 204} is the no-role signal — a {@code 200}-blank or a 4xx is "the resolve protocol is not
 * working as designed", never trusted as no-role. On a throw it WARNs with the HTTP status or the
 * exception class only — <strong>never</strong> the {@code userId}, token, or body — and wraps the cause
 * (for logs, never surfaced to the client). Built on the JDK {@link HttpClient} + Jackson — no
 * Feign/RestTemplate/WebClient.
 *
 * <h2>Resilience — B3 wraps the throw, never the classification</h2>
 * The HTTP exchange runs through the <strong>resolve</strong> {@link CallGuard} (Slice B3, ADR 0017): a
 * <em>transient</em> failure (5xx, 429, timeout, connection-refused) is retried within the resolve budget
 * <em>before</em> B2's throw fires, so a blip recovering within budget resolves instead of denying. B2 is
 * preserved <strong>exactly</strong>: {@code 204}→empty and {@code 200}+valid→resolved stay <em>terminal,
 * un-retried</em>; a {@code 4xx} (and a {@code 200}-blank / malformed-{@code 200}) is <strong>permanent,
 * thrown immediately with no retry</strong>; only an <em>exhausted</em> transient throws
 * {@code RoleResolutionException}. Retry is safe because resolve is a read-only GET (side-effect-free
 * invariant, ADR 0017 §3); the call runs on the request thread, outside any write transaction (no-lock
 * invariant, ADR 0017 §4). An open resolve breaker fails closed exactly like an exhausted outage.
 */
@Component
@ConditionalOnProperty(name = "catalog.role-source", havingValue = "http")
public class HttpRoleDefinitionSupplier implements RoleDefinitionSupplier {

    private static final Logger log = LoggerFactory.getLogger(HttpRoleDefinitionSupplier.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final CallGuard resolveGuard;

    /** The production wiring: the resolve {@link CallGuard} is injected (the B3 resilience budget). */
    @org.springframework.beans.factory.annotation.Autowired
    public HttpRoleDefinitionSupplier(
            ObjectMapper objectMapper,
            @Value("${catalog.user-service.base-url:http://localhost:8080}") String baseUrl,
            @Value("${catalog.user-service.timeout-ms:2000}") long timeoutMs,
            @Qualifier("resolveCallGuard") CallGuard resolveGuard) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.resolveGuard = resolveGuard;
    }

    /**
     * Test/demo constructor — no resilience (a single unguarded attempt, byte-identical to pre-B3). The
     * production bean uses the {@link CallGuard}-injecting constructor above.
     */
    public HttpRoleDefinitionSupplier(ObjectMapper objectMapper, String baseUrl, long timeoutMs) {
        this(objectMapper, baseUrl, timeoutMs, CallGuards.disabled("resolve"));
    }

    @Override
    public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
        if (userId == null || resourceType == null || resourceId == null) {
            return Optional.empty(); // no coordinates to resolve → authoritative no-role (not an outage)
        }
        URI uri = URI.create(baseUrl + "/internal/effective-role"
                + "?userId=" + enc(userId)
                + "&resourceType=" + enc(resourceType)
                + "&resourceId=" + enc(resourceId));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            // The body emits exactly one retryable signal — TransientResolveException — for the whole
            // transient subset (5xx/429, timeout, connection-refused; transport faults are caught and
            // re-thrown as it). Permanent failures (4xx/200-blank/malformed) throw RoleResolutionException
            // straight from the body → non-retryable → the guard re-throws at once. No raw IOException ever
            // escapes the body, so the predicate is just the TransientResolveException check.
            return resolveGuard.call(
                    () -> exchangeAndClassify(request),
                    t -> t instanceof TransientResolveException,
                    result -> false);
        } catch (TransientResolveException e) {
            // An exhausted transient outage (5xx/timeout/refused retried to the budget) — B2's outcome.
            throw new RoleResolutionException(e.getMessage(), e.getCause());
        } catch (CallNotPermittedException e) {
            // Open resolve breaker: fail closed exactly like an exhausted outage (ADR 0017 §5).
            log.warn("Effective-role resolve fail-closed: resolve circuit breaker open");
            throw new RoleResolutionException("effective-role source unavailable (breaker open)", e);
        }
    }

    /**
     * One HTTP exchange + B2's strict classification. Returns {@code Optional} for the two terminal success
     * signals (204, 200+valid); throws {@link RoleResolutionException} for a permanent failure (so the guard
     * re-throws immediately); throws {@link TransientResolveException} for a retryable transient (so the
     * guard retries, then maps an exhausted one back to {@code RoleResolutionException}).
     */
    private Optional<RoleDefinition> exchangeAndClassify(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // B2: transport failure (timeout, connection refused, reset) → outage. Transient → retryable.
            log.warn("Effective-role resolve failed ({}) — role-source outage, retrying/failing closed",
                    e.getClass().getSimpleName());
            throw new TransientResolveException("effective-role source unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore the interrupt flag before failing closed
            log.warn("Effective-role resolve interrupted — role-source outage, failing closed");
            throw new RoleResolutionException("effective-role resolve interrupted", e);
        }

        // B2 strict classification: ONLY 204 → no-role; ONLY 200+valid body → resolved; everything
        // else THROWS (outage → deny). 200-blank, all 4xx, all 5xx, any other status are untrustworthy.
        int status = response.statusCode();
        if (status == 204) {
            return Optional.empty(); // authoritative no-role → the realm fallback may decide (designed)
        }
        if (status == 200) {
            String body = response.body();
            if (body == null || body.isBlank()) {
                log.warn("Effective-role resolve returned 200 with a blank body — role-source outage");
                throw new RoleResolutionException("effective-role 200 with empty body"); // permanent
            }
            try {
                return Optional.of(objectMapper.readValue(body, RoleDefinition.class));
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                log.warn("Effective-role resolve 200 body was unparseable ({}) — role-source outage",
                        e.getClass().getSimpleName());
                throw new RoleResolutionException("effective-role 200 body unparseable", e); // permanent
            }
        }
        // A transient server-side status (5xx/429) is retryable; a permanent 4xx throws immediately.
        if (RetryableClassification.retryableStatus(status)) {
            log.warn("Effective-role resolve returned HTTP {} — transient, retrying/failing closed", status);
            throw new TransientResolveException("effective-role source returned HTTP " + status, null);
        }
        // Any non-204/non-200 status that is NOT retryable (every 4xx) → permanent outage, never no-role.
        log.warn("Effective-role resolve returned HTTP {} — role-source outage, failing closed", status);
        throw new RoleResolutionException("effective-role source returned HTTP " + status);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * A <em>retryable</em> resolve failure (transient 5xx/429/timeout/connection-refused). Thrown from the
     * exchange body so the resolve {@link CallGuard} retries it (the guard's error predicate matches this
     * type); an exhausted instance is mapped back to {@link RoleResolutionException} (B2's outcome) by
     * {@link #lookup}. Carries the transport {@code IOException} as the cause when transport-level, for logs.
     */
    static final class TransientResolveException extends RuntimeException {
        TransientResolveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
