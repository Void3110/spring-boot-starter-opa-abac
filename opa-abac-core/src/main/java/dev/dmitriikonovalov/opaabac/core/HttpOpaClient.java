package dev.dmitriikonovalov.opaabac.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OpaClient} backed by the JDK {@link HttpClient} and Jackson — zero extra dependencies, so
 * {@code opa-abac-core} stays Spring-free.
 *
 * <p>{@link #allow(AbacContext)} resolves the per-type document path, POSTs
 * {@code {"input": <context>}} to {@code <baseUrl>/v1/data/<path>}, and reads
 * {@code result.<decisionField>} as a boolean.
 *
 * <h2>Fail-closed</h2>
 * The cardinal rule: an authorization system that fails <em>open</em> is worse than none. Every error
 * — non-200, {@link java.io.IOException}, timeout, connection refused, malformed body, a missing or
 * non-boolean decision field — results in {@code false}. {@code allow(...)} never throws for an
 * OPA/transport failure; it logs a warning (path + status, never the token) and denies.
 */
public final class HttpOpaClient implements OpaClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOpaClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PolicyPathResolver pathResolver;
    private final OpaClientConfig config;

    /**
     * @param objectMapper a shared Jackson mapper (the context serializer)
     * @param pathResolver resolves the OPA data-document path per request
     * @param config       base URL, timeout, decision field
     */
    public HttpOpaClient(ObjectMapper objectMapper, PolicyPathResolver pathResolver, OpaClientConfig config) {
        this(defaultHttpClient(config), objectMapper, pathResolver, config);
    }

    /** Full constructor (lets a caller / test supply the {@link HttpClient}). */
    public HttpOpaClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            PolicyPathResolver pathResolver,
            OpaClientConfig config) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver");
        this.config = Objects.requireNonNull(config, "config");
    }

    private static HttpClient defaultHttpClient(OpaClientConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .build();
    }

    @Override
    public boolean allow(AbacContext context) {
        String path = null;
        try {
            path = pathResolver.resolve(context);
            URI uri = URI.create(config.baseUrl() + "/v1/data/" + path);
            byte[] body = objectMapper.writeValueAsBytes(new OpaInput(context));

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                log.warn("OPA denied (fail-closed): non-200 status {} for path '{}'", status, path);
                return false;
            }
            return readDecision(response.body(), path);
        } catch (Exception e) {
            // Fail-closed: any transport/serialization/timeout failure denies. Never leak the token.
            log.warn("OPA denied (fail-closed): {} for path '{}'", e.getClass().getSimpleName(), path);
            return false;
        }
    }

    private boolean readDecision(byte[] responseBody, String path) {
        try {
            OpaResult result = objectMapper.readValue(responseBody, OpaResult.class);
            Object decision = result.result() == null ? null : result.result().get(config.decisionField());
            if (decision instanceof Boolean allowed) {
                return allowed;
            }
            log.warn("OPA denied (fail-closed): missing/non-boolean '{}' in result for path '{}'",
                    config.decisionField(), path);
            return false;
        } catch (Exception e) {
            log.warn("OPA denied (fail-closed): malformed response ({}) for path '{}'",
                    e.getClass().getSimpleName(), path);
            return false;
        }
    }

    /** Explicit wrapper so the serialized request is {@code {"input": <context>}}. */
    private record OpaInput(AbacContext input) {}

    /**
     * OPA's {@code POST /v1/data/<path>} response: {@code {"result": {...}}}. The decision field is
     * read by name from the {@code result} map, so unknown fields are tolerated.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpaResult(@JsonProperty("result") Map<String, Object> result) {}
}
