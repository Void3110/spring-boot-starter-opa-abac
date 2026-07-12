package dev.dmitriikonovalov.example.catalog.config;

import tools.jackson.core.type.TypeReference;
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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches the tag definitions <b>applicable to a resource</b> from the user-management service's internal
 * API ({@code GET <base>/internal/tag-definitions?resourceType&resourceId}) so the catalog can validate
 * assigned tags against the dictionary. App code on the JDK {@link HttpClient} + Jackson — mirroring
 * {@code HttpRoleDefinitionSupplier}'s transport, no Feign/RestTemplate/WebClient.
 *
 * <h2>Fail-closed — but as a <em>rejection</em>, not an empty set</h2>
 * The role supplier fails closed to {@code Optional.empty()} (which the policy then default-denies). Tag
 * validation is the opposite shape: an empty definitions set would make <em>every</em> tag illegal-by-
 * absence or, worse, all-allowed — both wrong. So any fetch failure (non-200, timeout, connection
 * refused, malformed body) throws {@link TagDefinitionFetchException}; the controller turns that into a
 * 503 and nothing is persisted. A definitions outage never widens what is legal to assign.
 *
 * <h2>Resilience — B3 wraps the throw, never the contract</h2>
 * The HTTP exchange runs through the <strong>tag</strong> {@link CallGuard} (Slice B3, ADR 0017): a
 * <em>transient</em> failure (5xx, 429, timeout, connection-refused) is retried within the tag budget
 * <em>before</em> the throw, so a blip recovering within budget returns the definitions instead of a 503.
 * A {@code 4xx} (and a malformed/blank {@code 200}) is <strong>permanent — thrown immediately, no
 * retry</strong>; only an <em>exhausted</em> transient throws {@link TagDefinitionFetchException} → 503.
 * Retry is safe because the fetch is a read-only GET (side-effect-free, ADR 0017 §3); the call runs on the
 * request thread, outside any write transaction ({@code TagAssignmentService} is not {@code @Transactional}
 * — the no-lock invariant, ADR 0017 §4). An open tag breaker fails closed exactly like an exhausted outage.
 */
@Component
public class TagDefinitionClient {

    private static final Logger log = LoggerFactory.getLogger(TagDefinitionClient.class);
    private static final TypeReference<List<TagDefinitionView>> LIST_OF_DEFINITIONS =
            new TypeReference<>() {};

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final CallGuard tagGuard;

    /** The production wiring: the tag {@link CallGuard} is injected (the B3 resilience budget). */
    @org.springframework.beans.factory.annotation.Autowired
    public TagDefinitionClient(
            ObjectMapper objectMapper,
            @Value("${catalog.user-service.base-url:http://localhost:8080}") String baseUrl,
            @Value("${catalog.user-service.timeout-ms:2000}") long timeoutMs,
            @Qualifier("tagCallGuard") CallGuard tagGuard) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.tagGuard = tagGuard;
    }

    /**
     * Test/demo constructor — no resilience (a single unguarded attempt, byte-identical to pre-B3). The
     * production bean uses the {@link CallGuard}-injecting constructor above.
     */
    public TagDefinitionClient(ObjectMapper objectMapper, String baseUrl, long timeoutMs) {
        this(objectMapper, baseUrl, timeoutMs, CallGuards.disabled("tag"));
    }

    /**
     * The dictionary applicable to {@code (resourceType, resourceId)}: global keys + the governing team's
     * keys. Throws {@link TagDefinitionFetchException} on any failure (fail-closed → reject the write), but
     * only after retrying transients within the tag budget (B3).
     */
    public List<TagDefinitionView> fetchApplicable(String resourceType, String resourceId) {
        URI uri = URI.create(baseUrl + "/internal/tag-definitions"
                + "?resourceType=" + enc(resourceType)
                + "&resourceId=" + enc(resourceId));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            // The body emits exactly one retryable signal — TransientTagException — for the whole transient
            // subset (5xx/429, timeout, connection-refused). A permanent failure (4xx/blank/malformed) throws
            // TagDefinitionFetchException from the body → non-retryable → the guard re-throws at once.
            return tagGuard.call(
                    () -> exchangeAndParse(request),
                    t -> t instanceof TransientTagException,
                    result -> false);
        } catch (TransientTagException e) {
            // An exhausted transient outage (5xx/timeout/refused retried to the budget) → 503.
            throw new TagDefinitionFetchException(e.getMessage());
        } catch (CallNotPermittedException e) {
            log.warn("Tag-definitions fetch fail-closed: tag circuit breaker open (rejecting the write)");
            throw new TagDefinitionFetchException("Could not fetch tag definitions (breaker open)");
        }
    }

    /**
     * One HTTP exchange + parse. Returns the definitions on {@code 200}+valid; throws
     * {@link TagDefinitionFetchException} for a permanent failure (4xx/blank/malformed → the guard re-throws
     * at once); throws {@link TransientTagException} for a retryable transient (5xx/timeout/refused).
     */
    private List<TagDefinitionView> exchangeAndParse(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            log.warn("Tag-definitions fetch failed ({}), retrying/rejecting the write (fail-closed)",
                    e.getClass().getSimpleName());
            throw new TransientTagException("Could not fetch tag definitions", e); // transient
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Tag-definitions fetch interrupted, rejecting the write (fail-closed)");
            throw new TagDefinitionFetchException("Could not fetch tag definitions (interrupted)"); // permanent
        }

        int status = response.statusCode();
        if (status == 200 && response.body() != null && !response.body().isBlank()) {
            try {
                return objectMapper.readValue(response.body(), LIST_OF_DEFINITIONS);
            } catch (tools.jackson.core.JacksonException e) {
                log.warn("Tag-definitions fetch returned a malformed 200 body — rejecting the write");
                throw new TagDefinitionFetchException("Could not fetch tag definitions (malformed body)");
            }
        }
        // A transient server-side status (5xx/429) is retryable; a permanent 4xx / blank 200 throws now.
        if (RetryableClassification.retryableStatus(status)) {
            log.warn("Tag-definitions fetch returned HTTP {} — transient, retrying/rejecting the write", status);
            throw new TransientTagException("Could not fetch tag definitions (HTTP " + status + ")", null);
        }
        log.warn("Tag-definitions fetch returned HTTP {} — rejecting the write (fail-closed)", status);
        throw new TagDefinitionFetchException("Could not fetch tag definitions (HTTP " + status + ")");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * A <em>retryable</em> tag-fetch failure (transient 5xx/429/timeout/connection-refused). Thrown from the
     * exchange body so the tag {@link CallGuard} retries it; an exhausted instance is mapped back to
     * {@link TagDefinitionFetchException} (→ 503) by {@link #fetchApplicable}.
     */
    static final class TransientTagException extends RuntimeException {
        TransientTagException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
