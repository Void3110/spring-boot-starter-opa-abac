package dev.dmitriikonovalov.example.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The Phase-4 swap: a {@link RoleDefinitionSupplier} that resolves the caller's effective role by
 * calling the user-management service's internal resolve API
 * ({@code GET <base>/internal/effective-role?userId&resourceType&resourceId}). This is <b>app code</b>,
 * not a library change — the library SPI was built for exactly this single-bean swap; the demo supplier
 * stays available behind {@code catalog.role-source=demo} (the default).
 *
 * <h2>Fail-closed</h2>
 * The cardinal rule, end to end: a non-200 (incl. the 204 no-match), a timeout, a connection refused, a
 * malformed body — <em>any</em> failure resolves to {@link Optional#empty()}, which the
 * {@code @OpaPreAuthorize} manager treats as "no role definition" and the policy default-denies. This
 * method never throws for a transport/parse failure; it logs a warning (status only, never the token).
 * Built on the JDK {@link HttpClient} + Jackson — no Feign/RestTemplate/WebClient.
 */
@Component
@ConditionalOnProperty(name = "catalog.role-source", havingValue = "http")
public class HttpRoleDefinitionSupplier implements RoleDefinitionSupplier {

    private static final Logger log = LoggerFactory.getLogger(HttpRoleDefinitionSupplier.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;

    public HttpRoleDefinitionSupplier(
            ObjectMapper objectMapper,
            @Value("${catalog.user-service.base-url:http://localhost:8080}") String baseUrl,
            @Value("${catalog.user-service.timeout-ms:2000}") long timeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
        if (userId == null || resourceType == null || resourceId == null) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(baseUrl + "/internal/effective-role"
                    + "?userId=" + enc(userId)
                    + "&resourceType=" + enc(resourceType)
                    + "&resourceId=" + enc(resourceId));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 200 && response.body() != null && !response.body().isBlank()) {
                return Optional.of(objectMapper.readValue(response.body(), RoleDefinition.class));
            }
            if (status != 204 && status != 200) {
                log.warn("Effective-role resolve returned HTTP {} — failing closed (no role)", status);
            }
            return Optional.empty(); // 204 no-match, or 200 with an empty body → no role → default deny
        } catch (Exception e) {
            // Fail-closed: any transport/parse failure → no role → the policy default-denies.
            log.warn("Effective-role resolve failed ({}), failing closed", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
