package dev.dmitriikonovalov.example.catalog.config;

import tools.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fetches the resource ids a subject <b>supervises</b> — the second, disjoint access path onto the catalog
 * root list (ADR 0029) — by calling the user-management service's internal endpoint
 * {@code GET <base>/internal/supervised-targets?subject&resourceType → ["<uuid>", …]}.
 *
 * <p>App code on the JDK {@link HttpClient} + Jackson, modelled <b>directly</b> on
 * {@link HttpGovernedScopeResolver} (the membership sibling): same classification discipline, same timeout
 * handling, same interrupt-flag restoration. It is deliberately <em>not</em> a
 * {@code GovernedScopeResolver} — the membership resolver keeps its own single-bean identity, and the
 * supervised ids are composed <em>beside</em> it by {@code CatalogListAuthorizer} (T5), which is the only
 * place that knows both sets and can apply {@code supervised := S \ M}.
 *
 * <h2>Failure posture — fail-closed to empty, never throws</h2>
 * This supplies list <strong>scope</strong>, so the only safe failure value is the <strong>empty list</strong>
 * — exactly the base-scope SPI shape, not the role supplier's tri-state throw. Only a {@code 200} carrying a
 * valid JSON array of UUIDs yields ids; <em>every</em> other outcome is an empty list plus one WARN:
 * <ul>
 *   <li>a {@code 200} + {@code []} → empty — the authoritative "supervises nothing";</li>
 *   <li>any other status (4xx/5xx), a blank/unparseable body, a non-UUID element (a single bad element
 *       discards the whole result — never a <em>partial</em> supervised set), a timeout, a connection
 *       failure, an interrupt, a {@code null}/blank coordinate → empty.</li>
 * </ul>
 * The caller therefore degrades to <b>membership-only</b> on any supervised-edge failure (00-DESIGN's
 * failure class 1), never to a wider or a partial page. The WARN carries the HTTP status or the exception
 * class only — <strong>never</strong> the subject or the body.
 *
 * <h2>Resilience — its OWN breaker, and only the transport path counts as a failure</h2>
 * The exchange runs through the <strong>supervised</strong> {@link CallGuard} (Slice B3, ADR 0017) — a
 * <em>dedicated</em> guard instance built from the same {@code opa.abac.resilience.resolve.*} budget but
 * owning its own breaker. It deliberately does <b>not</b> reuse {@code resolveCallGuard}: that one guards
 * {@code /internal/effective-role}, so a supervised-targets outage tripping it would turn every persona's
 * role resolution into an empty page — a degrade-to-membership-only becoming a degrade-to-nothing.
 *
 * <p>A breaker failure is recorded <b>only on the thrown retryable path</b> (5xx/429, timeout,
 * connection-refused — surfaced as {@link TransientSupervisedException}). A fail-closed empty result is a
 * <em>decision</em>, not a transport failure, so it is returned as a terminal value and never counted
 * ({@code mx-951d2f}); the result predicate is therefore constantly {@code false}. An exhausted transient
 * and an open breaker both land on the same empty list.
 */
@Component
@ConditionalOnProperty(name = "catalog.role-source", havingValue = "http")
public class SupervisedScopeClient {

    private static final Logger log = LoggerFactory.getLogger(SupervisedScopeClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final CallGuard supervisedGuard;

    /**
     * The production wiring. The base URL is its <strong>own</strong> property —
     * {@code catalog.user-service.supervised-base-url}, defaulting to the shared
     * {@code catalog.user-service.base-url} so the shipped rig is unchanged. It must stay separate: the e2e
     * outage case (E8) faults <em>only</em> this edge by repointing it, which is impossible if the
     * supervised client reads the shared URL (B3's stub swaps the whole user-service the rest of the matrix
     * needs).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SupervisedScopeClient(
            ObjectMapper objectMapper,
            @Value("${catalog.user-service.supervised-base-url:"
                    + "${catalog.user-service.base-url:http://localhost:8080}}") String baseUrl,
            @Value("${catalog.user-service.timeout-ms:2000}") long timeoutMs,
            @Qualifier("supervisedCallGuard") CallGuard supervisedGuard) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.supervisedGuard = supervisedGuard;
    }

    /**
     * Test/demo constructor — no resilience (a single unguarded attempt). The production bean uses the
     * {@link CallGuard}-injecting constructor above.
     */
    public SupervisedScopeClient(ObjectMapper objectMapper, String baseUrl, long timeoutMs) {
        this(objectMapper, baseUrl, timeoutMs, CallGuards.disabled("supervised"));
    }

    /**
     * The ids of {@code resourceType} the subject supervises — distinct, order as the source returned them,
     * and <strong>empty</strong> on every non-affirmative outcome. Never {@code null}, never throws.
     *
     * <p>The returned set is the <em>raw</em> supervised set: the {@code supervised := S \ M} reduction
     * against the subject's memberships is the caller's (T5), because only the list authorizer knows both.
     */
    public List<UUID> supervisedIds(String subject, String resourceType) {
        if (subject == null || subject.isBlank() || resourceType == null || resourceType.isBlank()) {
            return List.of(); // no coordinates → fail-closed (supervises nothing); no call made
        }
        URI uri = URI.create(baseUrl + "/internal/supervised-targets"
                + "?subject=" + enc(subject)
                + "&resourceType=" + enc(resourceType));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            // The body emits exactly one retryable signal — TransientSupervisedException — for the whole
            // transient subset (5xx/429, timeout, connection-refused). Every permanent failure returns the
            // empty list from the body: a fail-closed decision, terminal and NOT a breaker failure.
            return supervisedGuard.call(
                    () -> exchangeAndParse(request),
                    TransientSupervisedException.class::isInstance,
                    result -> false);
        } catch (TransientSupervisedException e) {
            // An exhausted transient outage (5xx/timeout/refused retried to the budget) → membership-only.
            log.warn("supervised-targets fetch failed ({}) — failing closed to an empty supervised scope",
                    e.getMessage());
            return List.of();
        } catch (CallNotPermittedException _) {
            log.warn("supervised-targets fetch fail-closed: supervised circuit breaker open");
            return List.of();
        }
    }

    /**
     * One HTTP exchange + parse. Returns the ids on {@code 200}+valid and the <strong>empty list</strong> on
     * every permanent failure (4xx, blank/malformed body, interrupt) — terminal, no retry, no breaker
     * failure. Throws {@link TransientSupervisedException} for a retryable transient (5xx/429, timeout,
     * connection-refused) so the guard retries it and its breaker sees the outage.
     */
    private List<UUID> exchangeAndParse(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // Transport failure (timeout, connection refused, reset) → transient → retryable.
            throw new TransientSupervisedException(e.getClass().getSimpleName());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // restore the interrupt flag before failing closed
            log.warn("supervised-targets fetch interrupted — failing closed to an empty supervised scope");
            return List.of();
        }

        int status = response.statusCode();
        if (status == 200) {
            return parseUuids(response.body());
        }
        if (RetryableClassification.retryableStatus(status)) {
            throw new TransientSupervisedException("HTTP " + status); // transient → retry, count the breaker
        }
        log.warn("supervised-targets returned HTTP {} — failing closed to an empty supervised scope", status);
        return List.of();
    }

    /**
     * Parse a {@code ["uuid", …]} JSON array into {@link UUID}s. A blank/malformed body, a non-array, or a
     * non-UUID element fails closed to an empty list — a single bad element discards the whole result rather
     * than yielding a <strong>partial</strong> supervised set, which is indistinguishable from a correct
     * smaller one. Never throws.
     */
    private List<UUID> parseUuids(String body) {
        if (body == null || body.isBlank()) {
            log.warn("supervised-targets returned 200 with a blank body — failing closed to an empty scope");
            return List.of();
        }
        try {
            String[] raw = objectMapper.readValue(body, String[].class);
            List<UUID> ids = new ArrayList<>(raw.length);
            for (String s : raw) {
                if (s == null) {
                    // A null element is as malformed as a non-UUID one — discard the whole result.
                    throw new IllegalArgumentException("null element");
                }
                ids.add(UUID.fromString(s)); // a non-UUID element → IllegalArgumentException → empty (below)
            }
            return List.copyOf(ids);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException e) {
            log.warn("supervised-targets body was unparseable ({}) — failing closed to an empty scope",
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * A <em>retryable</em> supervised-targets failure (transient 5xx/429/timeout/connection-refused). Thrown
     * from the exchange body so the supervised {@link CallGuard} retries it and its breaker records the
     * outage; an exhausted instance is mapped back to the empty list by {@link #supervisedIds}. Permanent
     * failures never use this type — they return empty directly, so the breaker never sees a decision.
     */
    static final class TransientSupervisedException extends RuntimeException {
        TransientSupervisedException(String message) {
            super(message);
        }
    }
}
