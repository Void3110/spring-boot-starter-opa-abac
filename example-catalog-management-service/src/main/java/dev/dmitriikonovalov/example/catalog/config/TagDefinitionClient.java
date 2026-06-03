package dev.dmitriikonovalov.example.catalog.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public TagDefinitionClient(
            ObjectMapper objectMapper,
            @Value("${catalog.user-service.base-url:http://localhost:8080}") String baseUrl,
            @Value("${catalog.user-service.timeout-ms:2000}") long timeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /**
     * The dictionary applicable to {@code (resourceType, resourceId)}: global keys + the governing team's
     * keys. Throws {@link TagDefinitionFetchException} on any failure (fail-closed → reject the write).
     */
    public List<TagDefinitionView> fetchApplicable(String resourceType, String resourceId) {
        try {
            URI uri = URI.create(baseUrl + "/internal/tag-definitions"
                    + "?resourceType=" + enc(resourceType)
                    + "&resourceId=" + enc(resourceId));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status != 200 || response.body() == null || response.body().isBlank()) {
                log.warn("Tag-definitions fetch returned HTTP {} — rejecting the write (fail-closed)", status);
                throw new TagDefinitionFetchException(
                        "Could not fetch tag definitions (HTTP " + status + ")");
            }
            return objectMapper.readValue(response.body(), LIST_OF_DEFINITIONS);
        } catch (TagDefinitionFetchException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Tag-definitions fetch failed ({}), rejecting the write (fail-closed)",
                    e.getClass().getSimpleName());
            throw new TagDefinitionFetchException(
                    "Could not fetch tag definitions: " + e.getClass().getSimpleName());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
