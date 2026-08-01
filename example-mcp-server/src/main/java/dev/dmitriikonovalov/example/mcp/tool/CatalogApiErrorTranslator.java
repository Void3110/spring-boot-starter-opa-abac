package dev.dmitriikonovalov.example.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a catalog-service failure into a <strong>structured advisory</strong> {@link ToolInvocationException}.
 *
 * <p>The interesting case is {@code 403}: that is the catalog service's own per-type policy denying the
 * <em>principal</em> on the actual resource — the {@link ToolErrorLayer#TARGET_GATE}. Labelling it is what
 * lets a caller tell "you may not use this tool" apart from "you may use this tool, but not on that row",
 * which are different problems with different remedies. T4 adds the {@link ToolErrorLayer#TOOL_GATE} half
 * using the same vocabulary.
 *
 * <p>Only the upstream {@code errorCode} is carried across. The rest of the downstream body — its detail
 * text, instance path and any operational hint — stays in the log: a tool error is read by a model and, in
 * a real deployment, quite possibly by whoever is driving it, so it says what happened and nothing about
 * the internals of the service that decided it.
 */
public class CatalogApiErrorTranslator {

    static final String FORBIDDEN_CODE = "catalog-forbidden";
    static final String UNAUTHORIZED_CODE = "catalog-unauthorized";
    static final String NOT_FOUND_CODE = "catalog-not-found";
    static final String REJECTED_CODE = "catalog-request-rejected";
    static final String UNAVAILABLE_CODE = "catalog-unavailable";
    static final String UNREACHABLE_CODE = "catalog-unreachable";
    static final String MALFORMED_CODE = "catalog-malformed-response";

    private static final Logger log = LoggerFactory.getLogger(CatalogApiErrorTranslator.class);

    private final ObjectMapper objectMapper;

    public CatalogApiErrorTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Translate a non-2xx catalog response. */
    public ToolInvocationException translate(String path, int status, String body) {
        String upstreamCode = upstreamErrorCode(body);
        log.warn("Catalog call {} failed with HTTP {} (upstream errorCode={})", path, status, upstreamCode);

        if (status == 403) {
            return new ToolInvocationException(
                    ToolErrorLayer.TARGET_GATE,
                    upstreamCode != null ? upstreamCode : FORBIDDEN_CODE,
                    "The catalog service denied access to this resource for the calling principal.");
        }
        if (status == 401) {
            return new ToolInvocationException(
                    UNAUTHORIZED_CODE, "The catalog service did not accept the caller's token.");
        }
        if (status == 404) {
            return new ToolInvocationException(
                    upstreamCode != null ? upstreamCode : NOT_FOUND_CODE,
                    "The requested catalog resource does not exist.");
        }
        if (status >= 500) {
            return new ToolInvocationException(
                    UNAVAILABLE_CODE, "The catalog service is currently unavailable.");
        }
        return new ToolInvocationException(
                upstreamCode != null ? upstreamCode : REJECTED_CODE,
                "The catalog service rejected the request.");
    }

    /** Translate a transport-level failure — no response ever arrived. */
    public ToolInvocationException unreachable(String path, Throwable cause) {
        log.warn("Catalog call {} did not complete ({})", path, cause.getClass().getSimpleName());
        return new ToolInvocationException(
                UNREACHABLE_CODE, "The catalog service could not be reached.");
    }

    /** Translate a 2xx whose body could not be parsed. */
    public ToolInvocationException malformed(String path) {
        log.warn("Catalog call {} returned an unparseable body", path);
        return new ToolInvocationException(
                MALFORMED_CODE, "The catalog service returned an unreadable response.");
    }

    /** The upstream problem+json {@code errorCode}, or null when the body is absent/not problem+json. */
    private String upstreamErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode code = node.get("errorCode");
            return code != null && code.isString() && !code.stringValue().isBlank()
                    ? code.stringValue()
                    : null;
        } catch (JacksonException _) {
            return null;
        }
    }
}
