package dev.dmitriikonovalov.example.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Carries the <strong>structured</strong> detail of a tool-body failure across a seam that would
 * otherwise flatten it — from the throw site out to {@code ToolCallGate}, which builds the advisory the
 * caller sees.
 *
 * <h2>Why this exists (found on the rig, 2026-07-31)</h2>
 * A {@code @McpTool} method's exception never reaches the gate. Spring AI's annotation scanner builds the
 * specification's call handler, and <em>that</em> handler catches whatever the method throws and turns it
 * into a {@code CallToolResult} with {@code isError} set and a plain-text message
 * ({@code "Error invoking method: getCatalog…"}). By the time the gate's wrapper sees a value again, it is
 * an opaque error result: the {@link ToolErrorLayer} and the stable code are gone, so a target-gate denial
 * arrived indistinguishable from a transport fault — breaking exactly the property the slice exists to
 * demonstrate, that the caller can tell <em>which layer</em> said no.
 *
 * <p>The unit-level PEP tests could not catch this, because they build the specification themselves with
 * a handler that throws straight through. Only a real target-gate denial on the rig, through the real
 * annotation-scanned handler, exposes it — the call-path analogue of the {@code tools/list} routing gap.
 *
 * <h2>Request-scoped, deliberately</h2>
 * Scoping is delegated to the servlet request attributes, which die with the request — one
 * {@code tools/call} is one request, so there is no TTL, no cross-call store, and nothing to invalidate.
 * Outside a request (a unit test, a scheduler) every operation is a no-op and the gate falls back to its
 * direct {@code catch}, which is why nothing depends on this class being reachable.
 *
 * <p>It never changes a decision: it only restores <em>labelling</em> on a failure that has already
 * happened. A lost record degrades the advisory to a generic tool error — never to a permit.
 */
public final class ToolFailureRecord {

    private static final String ATTRIBUTE = ToolFailureRecord.class.getName() + ".failure";

    private static final Logger log = LoggerFactory.getLogger(ToolFailureRecord.class);

    private ToolFailureRecord() {
    }

    /** Remember a structured failure for the gate to read back. Called at the throw site. */
    public static void capture(ToolInvocationException failure) {
        RequestAttributes attributes = current();
        if (attributes == null) {
            return;
        }
        try {
            attributes.setAttribute(ATTRIBUTE, failure, RequestAttributes.SCOPE_REQUEST);
        } catch (RuntimeException e) {
            // Bookkeeping must never change an outcome — worst case the advisory is less specific.
            log.debug("Could not record the tool failure detail; the advisory will be generic", e);
        }
    }

    /** The failure recorded in this request, if any, clearing it so it is read exactly once. */
    public static ToolInvocationException take() {
        RequestAttributes attributes = current();
        if (attributes == null) {
            return null;
        }
        try {
            Object recorded = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            attributes.removeAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            return recorded instanceof ToolInvocationException failure ? failure : null;
        } catch (RuntimeException e) {
            log.debug("Could not read the recorded tool failure detail", e);
            return null;
        }
    }

    /** Drop any stale record before a tool body runs, so one call never reads another's failure. */
    public static void clear() {
        take();
    }

    private static RequestAttributes current() {
        try {
            return RequestContextHolder.getRequestAttributes();
        } catch (RuntimeException e) {
            log.debug("No request scope available for the tool failure record", e);
            return null;
        }
    }
}
