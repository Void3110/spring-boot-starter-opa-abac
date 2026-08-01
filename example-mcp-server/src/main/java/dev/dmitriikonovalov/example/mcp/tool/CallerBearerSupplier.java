package dev.dmitriikonovalov.example.mcp.tool;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Supplies the <strong>caller's own</strong> bearer token for the in-flight request.
 *
 * <p>The token is read off the current request and forwarded downstream byte-for-byte. Nothing here mints,
 * exchanges, or rewrites it: the catalog service must see exactly the credential the caller presented, so
 * that it re-derives the same principal and applies the same ceiling it would for a direct REST call. That
 * is what makes the two-layer composition sound — the downstream gate is not trusting this server, it is
 * evaluating the caller.
 *
 * <p>Fail-closed: a missing, blank, or non-{@code Bearer} authorization header throws rather than
 * returning null or an empty string, so a tool can never issue an <em>anonymous</em> downstream call that
 * some future permissive endpoint might answer.
 */
public class CallerBearerSupplier {

    static final String MISSING_BEARER_CODE = "caller-token-missing";

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * The raw bearer token (without the {@code Bearer } prefix) of the request being served.
     *
     * @throws ToolInvocationException when there is no current request, or it carries no bearer token
     */
    public String currentBearer() {
        HttpServletRequest request = currentRequest();
        String header = request == null ? null : request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new ToolInvocationException(
                    MISSING_BEARER_CODE, "The tool call carried no bearer token.");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new ToolInvocationException(
                    MISSING_BEARER_CODE, "The tool call carried no bearer token.");
        }
        return token;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
