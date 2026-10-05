package dev.dmitriikonovalov.opaabac.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
 * <h2>Fail-closed, and honest about why (ADR 0037)</h2>
 * The cardinal rule: an authorization system that fails <em>open</em> is worse than none. No path here
 * ever returns an allow it did not read from a {@code 200}. Every other outcome is one of two things, and
 * the two are kept apart:
 * <ul>
 *   <li><strong>A deny</strong> — the policy answered no ({@code allow: false}, or a loaded package whose
 *       {@code allow} is undefined for this input), or this client <em>refused to ask</em> because the
 *       request itself is defective (an unsafe policy path, a mixed-type batch, a context that will not
 *       serialize). A defect is deterministic and possibly caller-supplied, so it must keep answering no.</li>
 *   <li><strong>No decision</strong> — the engine could not be asked or did not answer with a decision:
 *       transport failure, timeout, a non-200, an interrupt, a body that is not a decision, or no
 *       {@code result} at all. These throw {@link PolicyEngineException} with the matching
 *       {@link PolicyEngineException.Kind}, logged once at WARN (path, kind, cause — never the token).</li>
 * </ul>
 */
public final class HttpOpaClient implements OpaClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOpaClient.class);

    /** The single declared unknown for partial evaluation — the row being filtered. */
    private static final List<String> UNKNOWNS = List.of("input.resource");

    /**
     * The optional structured-reason field of the decision envelope (ADR 0030 §6). Fixed, unlike the
     * configurable decision field: it is part of the library's own contract with the policy, not a
     * per-deployment naming choice.
     */
    private static final String DENY_REASON_FIELD = "deny_reason";

    private static final String RESULT_FIELD = "result";

    /** The operation names a failure's message and the WARN log carry. */
    private static final String OP_DECIDE = "decide";
    private static final String OP_COMPILE = "compile";
    private static final String OP_BULK = "bulk";

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
        return evaluate(context).allow();
    }

    /**
     * The same single decision, additionally reading the optional structured {@code deny_reason} from
     * the <em>same</em> response (ADR 0030 §6) — no second round-trip.
     *
     * <p>Never inventive: a reason reaches the caller only from a {@code 200} that denied with a
     * well-formed one. A malformed reason is dropped (a plain deny), never an error.
     */
    @Override
    public OpaDecision decide(AbacContext context) {
        return evaluate(context);
    }

    /**
     * The one evaluation path both {@link #allow} and {@link #decide} use.
     *
     * <p>Deliberately <em>not</em> written as {@code allow} delegating to {@code decide}: the interface's
     * {@code decide} default delegates to {@code allow}, so that shape would become an infinite recursion
     * the moment someone removed the override here — and a {@link StackOverflowError} is an
     * {@link Error}, which escapes every fail-closed handler rather than denying (the same reasoning as
     * {@link #isSafePath(String)}). A private helper both public methods call cannot form that cycle.
     */
    private OpaDecision evaluate(AbacContext context) {
        String path;
        byte[] body;
        try {
            path = requireSafePath(pathResolver.resolve(context));
            body = objectMapper.writeValueAsBytes(new OpaInput(context));
        } catch (DecisionIndeterminateException e) {
            throw e; // a path resolver that opted its own outage in (ADR 0037 §2)
        } catch (RuntimeException e) {
            log.warn("OPA denied (fail-closed): the request was not sent — {}", e.toString());
            return OpaDecision.deny();
        }
        HttpResponse<byte[]> response = post("/v1/data/" + path, body, OP_DECIDE, path);
        requireOk(response, OP_DECIDE, path);
        return readDecision(response.body(), path);
    }

    private OpaDecision readDecision(byte[] responseBody, String path) {
        JsonNode result = readResult(responseBody, OP_DECIDE, path);
        if (!result.isObject()) {
            throw indeterminate(PolicyEngineException.malformedResponse(
                    describe(OP_DECIDE, path) + ": 'result' is not an object", null));
        }
        JsonNode decision = result.get(config.decisionField());
        if (decision == null) {
            // Undefined-means-deny: the package is loaded and simply has no value for the decision field
            // on this input — OPA's idiom for a policy without a `default` (ADR 0037 §3). A real answer.
            log.debug("OPA denied: '{}' is undefined in the result for path '{}'", config.decisionField(), path);
            return OpaDecision.deny();
        }
        if (!decision.isBoolean()) {
            throw indeterminate(PolicyEngineException.malformedResponse(
                    describe(OP_DECIDE, path) + ": '" + config.decisionField() + "' is not a boolean", null));
        }
        if (decision.booleanValue()) {
            // An allow is an allow. A document carrying both is contradictory, and the reason — which only
            // ever means "a deny you could clear" — is dropped rather than passed on.
            return OpaDecision.permit();
        }
        return new OpaDecision(false, readDenyReason(result, path));
    }

    /**
     * Read {@code result.deny_reason}, or {@code null} if it is absent or not a well-formed reason.
     *
     * <p>The field types are checked by hand rather than handed to a converter: Jackson would happily
     * <em>coerce</em> a {@code "300"} string into {@code maxAge}, and a reason whose types are not what the
     * contract says is a policy the library does not understand. Dropping it costs a challenge and yields a
     * plain deny; trusting it would put a guessed window on the wire. Nothing here throws — the deny itself
     * is a real answer, and a parse problem in its optional reason must neither widen it nor turn it into an
     * error.
     */
    private DenyReason readDenyReason(JsonNode result, String path) {
        JsonNode raw = result.get(DENY_REASON_FIELD);
        if (raw == null || raw.isNull()) {
            return null; // the overwhelmingly common case: a plain deny
        }
        if (!raw.isObject()) {
            log.warn("OPA deny reason dropped (fail-closed): '{}' is not an object for path '{}'",
                    DENY_REASON_FIELD, path);
            return null;
        }
        String type = asString(raw.get("type"));
        String requiredAcr = asString(raw.get("required_acr"));
        Integer maxAge = asInteger(raw.get("max_age"));
        if (type == null || requiredAcr == null || maxAge == null) {
            log.warn("OPA deny reason dropped (fail-closed): malformed '{}' for path '{}'",
                    DENY_REASON_FIELD, path);
            return null;
        }
        return new DenyReason(type, requiredAcr, maxAge);
    }

    private static String asString(JsonNode value) {
        return value != null && value.isString() && !value.stringValue().isEmpty() ? value.stringValue() : null;
    }

    private static Integer asInteger(JsonNode value) {
        // A JSON integer that fits an int; a JSON float or a string is not a window this library will
        // advertise.
        return value != null && value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
    }

    /**
     * Partially evaluate the policy's {@code filter} rule with the resource declared unknown, returning
     * the residual. POSTs to {@code <baseUrl>/v1/compile} with
     * {@code {"query": "data.<path>.filter == true", "input": {…}, "unknowns": ["input.resource"]}}, the
     * resource omitted from {@code input}.
     *
     * <p>A failed call throws {@link PolicyEngineException}. A request this client refuses to send (an
     * unsafe path) returns {@link PartialResult#error()} — deny-all that also suppresses any widening a
     * caller composes with it. {@code {"result": {}}} stays {@link PartialResult#denyAll()}: partially
     * evaluating an undefined reference answers exactly that, so a missing package cannot be told apart
     * here (ADR 0037 §3a).
     */
    @Override
    public PartialResult compile(AbacContext context) {
        String path;
        byte[] body;
        try {
            path = requireSafePath(pathResolver.resolve(context));
            String query = "data." + path.replace('/', '.') + ".filter == true";
            body = objectMapper.writeValueAsBytes(new CompileRequest(query, new CompileInput(context), UNKNOWNS));
        } catch (DecisionIndeterminateException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("OPA compile denied (fail-closed): the request was not sent — {}", e.toString());
            return PartialResult.error(); // no policy answer: nothing may widen on top of it
        }
        HttpResponse<byte[]> response = post("/v1/compile", body, OP_COMPILE, path);
        requireOk(response, OP_COMPILE, path);
        JsonNode root = readObject(response.body(), OP_COMPILE, path);
        String resourceType = context.resource() == null ? null : context.resource().type();
        try {
            return new CompileResponseParser(resourceType).parse(root);
        } catch (RuntimeException e) {
            throw indeterminate(PolicyEngineException.malformedResponse(describe(OP_COMPILE, path, e), e));
        }
    }

    /**
     * Evaluate N decisions in one round-trip via the per-type {@code bulk} rule. POSTs
     * {@code {"input": {"items": [<ctx>, …]}}} to {@code <baseUrl>/v1/data/<path>/bulk} and reads
     * {@code result} as a boolean list of the same length. A failed call, a result of the wrong shape or
     * length, and a missing {@code result} (no such package, or no {@code bulk} rule in it) throw
     * {@link PolicyEngineException}; a mixed-type batch or an unsafe path is refused as all-false. An empty
     * input list returns an empty list with no HTTP call.
     */
    @Override
    public List<Boolean> allowAll(List<AbacContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        int n = contexts.size();
        String path;
        byte[] body;
        try {
            // All contexts in a batch must share one resource type (one list endpoint) — the first context
            // resolves the policy path for the whole batch. A mixed batch would silently evaluate every
            // item against the first item's policy, so it is refused outright (all-false, fail-closed).
            String batchType = resourceTypeOf(contexts.get(0));
            for (AbacContext context : contexts) {
                if (!Objects.equals(batchType, resourceTypeOf(context))) {
                    log.warn("OPA bulk denied (fail-closed): mixed resource types in one batch ('{}' vs '{}')",
                            batchType, resourceTypeOf(context));
                    return allFalse(n);
                }
            }
            path = requireSafePath(pathResolver.resolve(contexts.get(0)));
            body = objectMapper.writeValueAsBytes(new BulkInput(new BulkItems(contexts)));
        } catch (DecisionIndeterminateException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("OPA bulk denied (fail-closed): the request was not sent — {}", e.toString());
            return allFalse(n);
        }
        HttpResponse<byte[]> response = post("/v1/data/" + path + "/bulk", body, OP_BULK, path);
        requireOk(response, OP_BULK, path);
        return readBulkDecisions(response.body(), n, path);
    }

    private List<Boolean> readBulkDecisions(byte[] responseBody, int expected, String path) {
        JsonNode result = readResult(responseBody, OP_BULK, path);
        if (!result.isArray() || result.size() != expected) {
            throw indeterminate(PolicyEngineException.malformedResponse(
                    describe(OP_BULK, path) + ": 'result' is not a list of length " + expected, null));
        }
        List<Boolean> decisions = new java.util.ArrayList<>(expected);
        for (JsonNode element : result) {
            if (!element.isBoolean()) {
                throw indeterminate(PolicyEngineException.malformedResponse(
                        describe(OP_BULK, path) + ": a non-boolean element in 'result'", null));
            }
            decisions.add(element.booleanValue());
        }
        return List.copyOf(decisions);
    }

    /**
     * POST {@code body} to {@code <baseUrl><endpoint>}. Every way the exchange can fail becomes a
     * {@link PolicyEngineException} of the matching kind — including a request the {@link HttpClient}
     * rejects outright (a misconfigured base URL), which is no less "the engine could not be asked".
     */
    private HttpResponse<byte[]> post(String endpoint, byte[] body, String operation, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + endpoint))
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            // Both the request timeout and the connect timeout (HttpConnectTimeoutException extends it).
            throw indeterminate(PolicyEngineException.timeout(describe(operation, path, e), e));
        } catch (InterruptedException e) {
            // Interrupt-correct: restore the flag so the container's shutdown/cancellation signal survives.
            Thread.currentThread().interrupt();
            throw indeterminate(PolicyEngineException.interrupted(describe(operation, path, e), e));
        } catch (IOException | RuntimeException e) {
            // Any other transport failure, or a request the client rejects outright.
            throw indeterminate(PolicyEngineException.transport(describe(operation, path, e), e));
        }
    }

    private static void requireOk(HttpResponse<byte[]> response, String operation, String path) {
        int status = response.statusCode();
        if (status != 200) {
            throw indeterminate(PolicyEngineException.httpStatus(status, describe(operation, path) + ": status " + status));
        }
    }

    /** The response's {@code result} node: absent → {@code UNDEFINED_DECISION}; explicit null → malformed. */
    private JsonNode readResult(byte[] responseBody, String operation, String path) {
        JsonNode result = readObject(responseBody, operation, path).get(RESULT_FIELD);
        if (result == null) {
            throw indeterminate(PolicyEngineException.undefinedDecision(
                    describe(operation, path) + ": no 'result' — is the policy loaded at this path?"));
        }
        if (result.isNull()) {
            throw indeterminate(PolicyEngineException.malformedResponse(
                    describe(operation, path) + ": 'result' is null", null));
        }
        return result;
    }

    private JsonNode readObject(byte[] responseBody, String operation, String path) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (RuntimeException e) {
            throw indeterminate(PolicyEngineException.malformedResponse(describe(operation, path, e), e));
        }
        if (root == null || !root.isObject()) {
            throw indeterminate(PolicyEngineException.malformedResponse(
                    describe(operation, path) + ": the body is not a JSON object", null));
        }
        return root;
    }

    /** Log a failure once, at the point it is classified, and hand it back for throwing. */
    private static PolicyEngineException indeterminate(PolicyEngineException e) {
        log.warn("OPA decision indeterminate (fail-closed): {}", e.getMessage());
        log.debug("OPA call failed", e);
        return e;
    }

    private static String describe(String operation, String path) {
        return operation + " for path '" + path + "'";
    }

    private static String describe(String operation, String path, Throwable cause) {
        // The cause's class and message carry the URL/transport detail, never credentials.
        return describe(operation, path) + ": " + cause;
    }

    private static String resourceTypeOf(AbacContext context) {
        return context.resource() == null ? null : context.resource().type();
    }

    /** Throws on an unsafe/empty path; the caller turns that into a refusal (a deny), never an engine error. */
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
     * {@link Error}, not an {@link Exception}, so it would escape the refusal handlers in
     * {@link #allow}/{@link #compile}/{@link #allowAll} and propagate uncaught — turning a clean deny into
     * an unhandled failure. This scan runs in constant stack and O(n) time, and the length cap bounds n
     * regardless.
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
}
