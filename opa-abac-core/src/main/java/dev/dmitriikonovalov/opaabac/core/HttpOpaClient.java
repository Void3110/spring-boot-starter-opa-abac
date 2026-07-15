package dev.dmitriikonovalov.opaabac.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
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

    /** The single declared unknown for partial evaluation — the row being filtered. */
    private static final List<String> UNKNOWNS = List.of("input.resource");

    /**
     * The resolved policy path is interpolated into the request URI (and, for {@link #compile}, the
     * query string) — only {@code [A-Za-z0-9_-]} segments joined by single {@code /} are accepted,
     * whatever the {@link PolicyPathResolver} implementation returned. {@code .}/{@code ..} segments,
     * dots, or URL metacharacters in a resource type could otherwise address a different OPA document
     * or splice into the compile query. See {@link #isSafePath(String)} for why the check is a linear
     * scan and not a regex.
     */
    private static final int MAX_PATH_LENGTH = 512;

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
            path = requireSafePath(pathResolver.resolve(context));
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
        } catch (InterruptedException _) {
            // Fail-closed AND interrupt-correct: deny, but restore the flag so the container's
            // shutdown/cancellation signal survives this call.
            Thread.currentThread().interrupt();
            log.warn("OPA denied (fail-closed): interrupted for path '{}'", path);
            return false;
        } catch (Exception e) {
            // Fail-closed: any transport/serialization/timeout failure denies. Never log the token —
            // exception class + message carry the URL/transport detail, not credentials.
            log.warn("OPA denied (fail-closed): {} for path '{}'", e, path);
            log.debug("OPA allow call failed", e);
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

    /**
     * Partially evaluate the policy's {@code filter} rule with the resource declared unknown, returning
     * the residual. POSTs to {@code <baseUrl>/v1/compile} with
     * {@code {"query": "data.<path>.filter == true", "input": {…}, "unknowns": ["input.resource"]}}, the
     * resource omitted from {@code input}. A failed call (transport, non-200, unparseable body) fails
     * closed to {@link PartialResult#error()} — deny-all, flagged {@code fromError} so callers suppress
     * any widening too.
     */
    @Override
    public PartialResult compile(AbacContext context) {
        String path = null;
        try {
            path = requireSafePath(pathResolver.resolve(context));
            String query = "data." + path.replace('/', '.') + ".filter == true";
            URI uri = URI.create(config.baseUrl() + "/v1/compile");
            byte[] body = objectMapper.writeValueAsBytes(new CompileRequest(query, new CompileInput(context), UNKNOWNS));

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                log.warn("OPA compile denied (fail-closed): non-200 status {} for path '{}'", status, path);
                return PartialResult.error();
            }
            String resourceType = context.resource() == null ? null : context.resource().type();
            JsonNode root = objectMapper.readTree(response.body());
            return new CompileResponseParser(resourceType).parse(root);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.warn("OPA compile denied (fail-closed): interrupted for path '{}'", path);
            return PartialResult.error();
        } catch (Exception e) {
            // Fail-closed: a compile/transport/parse failure must never widen visibility. The result is
            // flagged fromError so callers also suppress any widening composed alongside the residual.
            log.warn("OPA compile denied (fail-closed): {} for path '{}'", e, path);
            log.debug("OPA compile call failed", e);
            return PartialResult.error();
        }
    }

    /**
     * Evaluate N decisions in one round-trip via the per-type {@code bulk} rule. POSTs
     * {@code {"input": {"items": [<ctx>, …]}}} to {@code <baseUrl>/v1/data/<path>/bulk} and reads
     * {@code result} as a boolean list of the same length. Fails closed to all-false on any error or a
     * length mismatch; an empty input list returns an empty list with no HTTP call.
     */
    @Override
    public List<Boolean> allowAll(List<AbacContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        int n = contexts.size();
        String path = null;
        try {
            // All contexts in a batch must share one resource type (one list endpoint) — the first context
            // resolves the policy path for the whole batch. A mixed batch would silently evaluate every
            // item against the first item's policy, so it is rejected outright (all-false, fail-closed).
            String batchType = resourceTypeOf(contexts.get(0));
            for (AbacContext context : contexts) {
                if (!Objects.equals(batchType, resourceTypeOf(context))) {
                    log.warn("OPA bulk denied (fail-closed): mixed resource types in one batch ('{}' vs '{}')",
                            batchType, resourceTypeOf(context));
                    return allFalse(n);
                }
            }
            path = requireSafePath(pathResolver.resolve(contexts.get(0)));
            URI uri = URI.create(config.baseUrl() + "/v1/data/" + path + "/bulk");
            byte[] body = objectMapper.writeValueAsBytes(new BulkInput(new BulkItems(contexts)));

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                log.warn("OPA bulk denied (fail-closed): non-200 status {} for path '{}'", status, path);
                return allFalse(n);
            }
            return readBulkDecisions(response.body(), n, path);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.warn("OPA bulk denied (fail-closed): interrupted for path '{}'", path);
            return allFalse(n);
        } catch (Exception e) {
            log.warn("OPA bulk denied (fail-closed): {} for path '{}'", e, path);
            log.debug("OPA bulk call failed", e);
            return allFalse(n);
        }
    }

    private static String resourceTypeOf(AbacContext context) {
        return context.resource() == null ? null : context.resource().type();
    }

    /** Throws on an unsafe/empty path; the caller's fail-closed catch turns that into a deny. */
    private static String requireSafePath(String path) {
        if (!isSafePath(path)) {
            throw new IllegalArgumentException("unsafe OPA policy path '" + path + "'");
        }
        return path;
    }

    /**
     * Accept a policy path of {@code [A-Za-z0-9_-]} segments joined by single {@code /}, with no
     * leading/trailing/empty segment — the same grammar an anchored
     * {@code [A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*} regex would accept.
     *
     * <p>Deliberately a single linear scan, not a {@link Pattern}: that regex's {@code (…/…)*} group
     * compiles to a recursive match in {@code java.util.regex}, so a long resolver-derived path
     * (thousands of segments) overflows the stack with a {@link StackOverflowError}. That is an
     * {@link Error}, not an {@link Exception}, so it would escape the {@code catch (Exception)}
     * fail-closed handlers in {@link #allow}/{@link #compile}/{@link #allowAll} and propagate uncaught
     * — turning a clean deny into an unhandled failure. This scan runs in constant stack and O(n) time,
     * and the length cap bounds n regardless.
     */
    private static boolean isSafePath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        boolean prevWasSlash = true; // treat start-of-string like a slash: forbids a leading '/'
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (prevWasSlash) {
                    return false; // leading slash or an empty segment ("a//b")
                }
                prevWasSlash = true;
            } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                prevWasSlash = false;
            } else {
                return false; // any other character
            }
        }
        return !prevWasSlash; // a trailing '/' leaves prevWasSlash true
    }

    private List<Boolean> readBulkDecisions(byte[] responseBody, int expected, String path) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode result = root.get("result");
            if (result == null || !result.isArray() || result.size() != expected) {
                log.warn("OPA bulk denied (fail-closed): result is not a boolean list of length {} for path '{}'",
                        expected, path);
                return allFalse(expected);
            }
            List<Boolean> decisions = new java.util.ArrayList<>(expected);
            for (JsonNode element : result) {
                if (!element.isBoolean()) {
                    log.warn("OPA bulk denied (fail-closed): non-boolean element in result for path '{}'", path);
                    return allFalse(expected);
                }
                decisions.add(element.asBoolean());
            }
            return List.copyOf(decisions);
        } catch (Exception e) {
            log.warn("OPA bulk denied (fail-closed): malformed response ({}) for path '{}'",
                    e.getClass().getSimpleName(), path);
            return allFalse(expected);
        }
    }

    private static List<Boolean> allFalse(int n) {
        Boolean[] values = new Boolean[n];
        java.util.Arrays.fill(values, Boolean.FALSE);
        return List.of(values);
    }

    /** Explicit wrapper so the serialized request is {@code {"input": <context>}}. */
    private record OpaInput(AbacContext input) {}

    /** The OPA Compile API request: {@code {"query": …, "input": …, "unknowns": […]}}. */
    private record CompileRequest(String query, CompileInput input, List<String> unknowns) {}

    /**
     * The compile {@code input}: subject/action/role_definition are known; the <em>resource is omitted</em>
     * (it is the unknown). Serializes the same {@link AbacContext} but suppresses {@code resource}.
     */
    private record CompileInput(
            AbacContext.Subject subject,
            String action,
            @JsonProperty("role_definition") @JsonInclude(JsonInclude.Include.NON_NULL) RoleDefinition roleDefinition,
            Map<String, Object> environment) {
        CompileInput(AbacContext context) {
            this(context.subject(), context.action(), context.roleDefinition(), context.environment());
        }
    }

    /** The bulk request wrapper: {@code {"input": {"items": […]}}}. */
    private record BulkInput(BulkItems input) {}

    /** The bulk items list the {@code bulk} rule iterates: {@code {"items": [<ctx>, …]}}. */
    private record BulkItems(List<AbacContext> items) {}

    /**
     * OPA's {@code POST /v1/data/<path>} response: {@code {"result": {...}}}. The decision field is
     * read by name from the {@code result} map, so unknown fields are tolerated.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpaResult(@JsonProperty("result") Map<String, Object> result) {}
}
