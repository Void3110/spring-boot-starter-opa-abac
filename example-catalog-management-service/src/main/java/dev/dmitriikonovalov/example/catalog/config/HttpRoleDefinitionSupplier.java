package dev.dmitriikonovalov.example.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
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
 * (for logs, never surfaced to the client). Resilience (retry / backoff / timeout tuning) is out of scope
 * here — that is Slice B3. Built on the JDK {@link HttpClient} + Jackson — no Feign/RestTemplate/WebClient.
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

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // B2: transport failure (timeout, connection refused, reset) → outage, not no-role.
            log.warn("Effective-role resolve failed ({}) — role-source outage, failing closed",
                    e.getClass().getSimpleName());
            throw new RoleResolutionException("effective-role source unavailable", e);
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
                throw new RoleResolutionException("effective-role 200 with empty body");
            }
            try {
                return Optional.of(objectMapper.readValue(body, RoleDefinition.class));
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                log.warn("Effective-role resolve 200 body was unparseable ({}) — role-source outage",
                        e.getClass().getSimpleName());
                throw new RoleResolutionException("effective-role 200 body unparseable", e);
            }
        }
        // Any non-204/non-200 status (4xx, 5xx, anything else) → outage, never no-role.
        log.warn("Effective-role resolve returned HTTP {} — role-source outage, failing closed", status);
        throw new RoleResolutionException("effective-role source returned HTTP " + status);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
