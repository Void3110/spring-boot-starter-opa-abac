package dev.dmitriikonovalov.example.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The catalog example's {@link GovernedScopeResolver} impl: resolves the catalog ids a subject governs
 * (via team membership) by calling the user-management service's internal endpoint
 * {@code GET <base>/internal/governed-targets?subject&resourceType=catalog → ["<uuid>", …]}. It implements
 * the {@link GovernedScopeResolver#governedIds} primitive; the {@code id IN (those uuids)} base scope comes
 * from the SPI's default {@code governedScope} (Slice B4, ADR 0018).
 *
 * <p>This is <b>app code</b>, not a library change — the library SPI was built for exactly this single-bean
 * swap, mirroring {@link HttpRoleDefinitionSupplier} (the JDK {@link HttpClient} + Jackson client style; no
 * Feign/RestTemplate/WebClient). It shares the same {@code catalog.user-service.*} coordinates.
 *
 * <h2>Failure posture — fail-closed to empty, never throws (the SPI contract; ADR 0018 §5)</h2>
 * Unlike the role-resolve supplier's <em>tri-state-throw</em> (B2) — which throws so the caller can
 * distinguish an outage from no-role — this resolver supplies the <strong>base scope</strong> of a list, so
 * the only safe failure value is the <strong>empty list</strong>. <em>Every</em> non-affirmative outcome
 * collapses to {@link GovernedScopeResolver#denyAll()} (an always-false Specification), with <b>no throw</b>:
 * <ul>
 *   <li>a {@code 200} with a valid JSON array → {@code id IN (ids)} (an <em>empty</em> array is the
 *       authoritative "governs nothing" → {@code denyAll()});</li>
 *   <li>any other status (4xx/5xx), a blank/malformed body, a timeout, a connection failure, a
 *       {@code null}/blank subject → {@code denyAll()}.</li>
 * </ul>
 * A transport outage therefore yields the <em>same</em> empty page as "governs nothing" — never the whole
 * table, never a 500. The WARN carries the HTTP status or the exception class only — <strong>never</strong>
 * the {@code subject} or the body. This is consistent with the B2/B3 fail-closed discipline (the
 * <em>outcome</em> is the safe value on every breach); resilience-retrying the GET (B3-style) is a possible
 * later refinement — it would only change <em>how often</em> a transient blip lands on the empty floor, not
 * the floor itself.
 */
@Component
@ConditionalOnProperty(name = "catalog.role-source", havingValue = "http")
public class HttpGovernedScopeResolver implements GovernedScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(HttpGovernedScopeResolver.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;

    public HttpGovernedScopeResolver(
            ObjectMapper objectMapper,
            @Value("${catalog.user-service.base-url:http://localhost:8080}") String baseUrl,
            @Value("${catalog.user-service.timeout-ms:2000}") long timeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public List<UUID> governedIds(String subject, String resourceType) {
        if (subject == null || subject.isBlank() || resourceType == null || resourceType.isBlank()) {
            return List.of(); // no coordinates → fail-closed (governs nothing); the SPI default → denyAll
        }
        return fetchGovernedIds(subject, resourceType);
    }

    /**
     * The governed ids for {@code (subject, resourceType)}, or an <strong>empty</strong> list on every
     * non-affirmative outcome (never throws). An empty list is indistinguishable from "governs nothing" —
     * both fail closed to {@link GovernedScopeResolver#denyAll()} at the call site.
     */
    private List<UUID> fetchGovernedIds(String subject, String resourceType) {
        URI uri = URI.create(baseUrl + "/internal/governed-targets"
                + "?subject=" + enc(subject)
                + "&resourceType=" + enc(resourceType));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status != 200) {
                log.warn("governed-targets returned HTTP {} — failing closed to empty scope", status);
                return List.of();
            }
            return parseUuids(response.body());
        } catch (java.io.IOException e) {
            log.warn("governed-targets fetch failed ({}) — failing closed to empty scope",
                    e.getClass().getSimpleName());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore the interrupt flag before failing closed
            log.warn("governed-targets fetch interrupted — failing closed to empty scope");
            return List.of();
        }
    }

    /**
     * Parse a {@code ["uuid", …]} JSON array into {@link UUID}s. A blank/malformed body, a non-array, or a
     * non-UUID element fails closed to an empty list (a single bad element discards the whole result rather
     * than silently widening on a partial parse). Never throws.
     */
    private List<UUID> parseUuids(String body) {
        if (body == null || body.isBlank()) {
            log.warn("governed-targets returned 200 with a blank body — failing closed to empty scope");
            return List.of();
        }
        try {
            String[] raw = objectMapper.readValue(body, String[].class);
            List<UUID> ids = new ArrayList<>(raw.length);
            for (String s : raw) {
                ids.add(UUID.fromString(s)); // a non-UUID element → IllegalArgumentException → empty (below)
            }
            return ids;
        } catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException e) {
            log.warn("governed-targets body was unparseable ({}) — failing closed to empty scope",
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
}
