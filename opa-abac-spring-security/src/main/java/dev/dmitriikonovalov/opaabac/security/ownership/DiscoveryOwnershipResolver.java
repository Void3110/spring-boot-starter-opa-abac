package dev.dmitriikonovalov.opaabac.security.ownership;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link ResourceOwnershipResolver}: discovers the owning service from a config registry
 * ({@link OwnershipProperties#getServices()}), reads the standard {@code GET /internal/{type}/{id}/created-by}
 * contract, and compares {@code createdBy} to the caller — with a short-TTL cache on the subject-independent
 * {@code (type,id) → createdBy} result (Slice B4, ADR 0019).
 *
 * <h2>Resolution</h2>
 * <ol>
 *   <li>Look up the owning service's base URL by {@code resourceType}. No entry → <strong>unknown type</strong>
 *       → {@code false}, <em>no call made</em> (the resolver never guesses a URL).</li>
 *   <li>Read {@code (type,id) → createdBy} from the cache, or fetch {@code GET <base>/internal/{type}/{id}/created-by}
 *       → {@code 200 {"createdBy":"<sub>"}} (cache it) / {@code 404} (no such resource → not owner) / any other
 *       outcome (unreachable, 5xx, blank/malformed body → not owner). Fetch failures are <strong>not cached</strong>
 *       (only an authoritative {@code 200}/{@code 404} result is), so a transient outage doesn't pin a wrong
 *       answer for the whole TTL.</li>
 *   <li>Compare: {@code createdBy.equals(subject)} → owner; else not.</li>
 * </ol>
 *
 * <h2>Fail-closed (ADR 0019)</h2>
 * Every non-affirmative outcome → {@code false}, and the resolver <strong>never throws past its boundary</strong>
 * (a throw a caller might catch-and-allow would re-open squatting). A {@code null}/blank subject, a null type/id,
 * an unknown type, an unreachable service, a {@code 404}, a blank/malformed body, or a {@code createdBy} mismatch
 * all map to {@code false}. The cache stores only authoritative results (present-{@code createdBy} or
 * resource-absent), never an outage.
 *
 * <h2>Cache &amp; clock</h2>
 * The cache key is {@code (type,id)} — subject-independent, so two callers checking the same resource share the
 * hit (good ratio). Entries expire after {@link OwnershipProperties#getTtl()} measured against an injected
 * {@link Clock} (system clock in production; a virtual clock in tests, so TTL behavior is asserted with zero
 * {@code Thread.sleep} and no wall-clock flakiness). Ownership-transfer staleness up to the TTL is a documented
 * trade (ADR 0019); event-invalidation is a follow-up.
 */
public class DiscoveryOwnershipResolver implements ResourceOwnershipResolver {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryOwnershipResolver.class);

    /** A cached created-by lookup: the creator sub (may be {@code null} = resource exists but no creator)
     *  and the instant the entry was written (for TTL). A resource-absent (404) result is NOT cached as an
     *  entry — only authoritative present results are, so a deleted-then-recreated resource isn't pinned. */
    private record CacheEntry(String createdBy, long writtenAtMillis) {}

    private final OwnershipProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;
    private final Duration timeout;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Production constructor: the system UTC clock. */
    public DiscoveryOwnershipResolver(OwnershipProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    /**
     * Full constructor — a test supplies a virtual {@link Clock} so TTL expiry is asserted at virtual time.
     */
    public DiscoveryOwnershipResolver(
            OwnershipProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.timeout = Duration.ofMillis(properties.getTimeoutMs());
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public boolean isOwner(String subject, String resourceType, UUID resourceId) {
        if (subject == null || subject.isBlank() || resourceType == null || resourceId == null) {
            return false; // no coordinates → fail-closed
        }
        String baseUrl = properties.getServices().get(resourceType);
        if (baseUrl == null) {
            // Unknown type: no owning service configured. Fail closed WITHOUT a call (never guess a URL).
            log.debug("ownership check: no service registered for type '{}' — not owner", resourceType);
            return false;
        }
        Optional<String> createdBy = lookupCreatedBy(stripTrailingSlash(baseUrl), resourceType, resourceId);
        return createdBy.map(subject::equals).orElse(false);
    }

    /**
     * The cached-or-fetched creator sub for {@code (type,id)}. {@link Optional#empty()} on every
     * non-affirmative outcome (unreachable / 404 / blank / malformed). Only an authoritative present result
     * is cached; a transient failure is not (so it doesn't pin a wrong answer for the TTL).
     */
    private Optional<String> lookupCreatedBy(String baseUrl, String resourceType, UUID resourceId) {
        String key = resourceType + "/" + resourceId;
        CacheEntry cached = cache.get(key);
        if (cached != null && !isExpired(cached)) {
            return Optional.ofNullable(cached.createdBy());
        }
        if (cached != null) {
            cache.remove(key); // expired — evict before re-fetch
        }
        Optional<String> fetched = fetchCreatedBy(baseUrl, resourceType, resourceId);
        fetched.ifPresent(createdBy -> cache.put(key, new CacheEntry(createdBy, nowMillis())));
        return fetched;
    }

    private Optional<String> fetchCreatedBy(String baseUrl, String resourceType, UUID resourceId) {
        URI uri = URI.create(baseUrl + "/internal/" + resourceType + "/" + resourceId + "/created-by");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 404) {
                return Optional.empty(); // no such resource → not owner (authoritative, but not cached)
            }
            if (status != 200) {
                log.warn("created-by read returned HTTP {} for type '{}' — not owner (fail-closed)",
                        status, resourceType);
                return Optional.empty();
            }
            return parseCreatedBy(response.body());
        } catch (java.io.IOException e) {
            log.warn("created-by read failed ({}) for type '{}' — not owner (fail-closed)",
                    e.getClass().getSimpleName(), resourceType);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore the flag before failing closed
            log.warn("created-by read interrupted for type '{}' — not owner (fail-closed)", resourceType);
            return Optional.empty();
        }
    }

    /** Parse {@code {"createdBy":"<sub>"}}. A blank/malformed body or absent/blank field → empty (not owner). */
    private Optional<String> parseCreatedBy(String body) {
        if (body == null || body.isBlank()) {
            log.warn("created-by read returned 200 with a blank body — not owner (fail-closed)");
            return Optional.empty();
        }
        try {
            var node = objectMapper.readTree(body).get("createdBy");
            if (node == null || node.isNull() || node.asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(node.asText());
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            log.warn("created-by body unparseable ({}) — not owner (fail-closed)",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean isExpired(CacheEntry entry) {
        return nowMillis() - entry.writtenAtMillis() >= properties.getTtl().toMillis();
    }

    private long nowMillis() {
        return clock.millis();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
