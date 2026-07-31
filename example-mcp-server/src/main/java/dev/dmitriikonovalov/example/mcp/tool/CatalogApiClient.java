package dev.dmitriikonovalov.example.mcp.tool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The tools' one outbound edge: read-only calls to the <strong>existing</strong> catalog-management
 * service's REST API, carrying the caller's own bearer.
 *
 * <p>App code on the JDK {@link HttpClient} + Jackson, mirroring {@code TagDefinitionClient} and
 * {@code HttpRoleDefinitionSupplier} — the repo's established transport for service-to-service reads.
 *
 * <h2>The caller's bearer, and nothing else</h2>
 * Exactly two headers go out: {@code Authorization} with the caller's token forwarded verbatim, and
 * {@code Accept}. No role, capability, delegation chain, or "acting-as" header is ever added — the catalog
 * service must not be asked to trust anything this server asserts. Its policies re-derive the principal
 * from the token and enforce that principal's ceiling exactly as they do for a direct REST call, which is
 * what makes the intersection hold <em>across</em> the two layers instead of being handed between them
 * (the caller-supplied-role shape slice B4 removed is not reintroduced).
 *
 * <h2>Failures are structured, never raw</h2>
 * Every outcome that is not a parseable 2xx becomes a {@link ToolInvocationException} through
 * {@link CatalogApiErrorTranslator}: a downstream {@code 403} carries the {@code target-gate} label, and a
 * timeout or refused connection becomes a bounded, advisory error rather than a hang or a stack trace.
 */
public class CatalogApiClient {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CallerBearerSupplier bearerSupplier;
    private final CatalogApiErrorTranslator errorTranslator;
    private final String baseUrl;
    private final Duration readTimeout;

    public CatalogApiClient(
            ObjectMapper objectMapper,
            CallerBearerSupplier bearerSupplier,
            CatalogApiErrorTranslator errorTranslator,
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout) {
        this.objectMapper = objectMapper;
        this.bearerSupplier = bearerSupplier;
        this.errorTranslator = errorTranslator;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.readTimeout = readTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /**
     * {@code GET <base><path>} with the caller's bearer, parsed as a JSON object.
     *
     * @param path an already-built, absolute path beginning with {@code /} (build it with {@link #segment})
     * @throws ToolInvocationException on any non-2xx, unreachable host, timeout, or unparseable body
     */
    public Map<String, Object> getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + bearerSupplier.currentBearer())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw recorded(errorTranslator.unreachable(path, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw recorded(errorTranslator.unreachable(path, e));
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw recorded(errorTranslator.translate(path, status, response.body()));
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw recorded(errorTranslator.malformed(path));
        }
        try {
            return objectMapper.readValue(body, JSON_OBJECT);
        } catch (JacksonException _) {
            throw recorded(errorTranslator.malformed(path));
        }
    }

    /**
     * Percent-encode one path segment, so a tool argument can never inject a path or a query.
     *
     * <p>{@link URLEncoder} targets form encoding, where a space is {@code +}; in a path a {@code +} is a
     * literal plus, so it is re-encoded to {@code %20} here.
     */
    public static String segment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Record the structured detail before throwing.
     *
     * <p>Spring AI's annotation-scanned call handler catches whatever a {@code @McpTool} method throws
     * and flattens it into a plain-text error result, so {@code ToolCallGate}'s own {@code catch} never
     * sees this exception in the real invocation path. {@link ToolFailureRecord} carries the layer and
     * the code across that seam so a target-gate denial stays distinguishable from a transport fault.
     */
    private static ToolInvocationException recorded(ToolInvocationException failure) {
        ToolFailureRecord.capture(failure);
        return failure;
    }
}
