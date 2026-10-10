package dev.dmitriikonovalov.opaabac.mcp.authz;

import dev.dmitriikonovalov.opaabac.mcp.tool.ToolErrorLayer;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolFailureRecord;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolInvocationException;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The one invocation rule the four tool-gate wrappers share — {@link ToolCallGate},
 * {@link AsyncToolCallGate}, {@link StatelessToolCallGate} and {@link StatelessAsyncToolCallGate} differ
 * only in the shape of their handler's first argument and whether it answers with a {@link Mono}.
 *
 * <h2>Decide on invocation, never inside the {@code Mono}</h2>
 * The decision is taken when the handler is <em>invoked</em> — on the thread the transport called it on,
 * where the caller's {@code SecurityContextHolder} and request attributes are bound — and before any
 * {@code Mono} exists. An async wrapper then returns either the denial ({@code Mono.just(advisory)}) or the
 * delegate's own {@code Mono}, <strong>decorated, never replaced</strong>. Nothing in the decoration reads
 * the caller's identity, so a resubscription never re-decides and a scheduler hop after the decision
 * cannot change it.
 *
 * <h2>What the async decoration does — and only that</h2>
 * <ul>
 *   <li>an emitted {@link ToolInvocationException} becomes the same advisory a synchronous throw produces;</li>
 *   <li>an emitted error <em>result</em> is relabelled from {@link ToolFailureRecord}, read <strong>at
 *       emission</strong> — the tool body of an async specification runs at subscription, so the record
 *       cannot exist when the handler is invoked; with no request bound at emission the record reads as
 *       absent and the result passes through unrelabelled;</li>
 *   <li>any other emitted error, and an empty completion, pass through <strong>unchanged</strong> — the
 *       authorization already happened, and the gate is not a result validator.</li>
 * </ul>
 * Every rewrite produces an {@code isError} result: nothing here can turn an error into a success.
 */
final class ToolGateInvocation {

    /** One log category for every wrapper (the {@code …mcp.authz} package carries the gate's other logs). */
    private static final Logger log = LoggerFactory.getLogger(ToolGateInvocation.class);

    private ToolGateInvocation() {
    }

    /** The synchronous rule: decide, then run the body on this thread and relabel its result. */
    static <C> CallToolResult invokeSync(
            String toolName,
            ToolCallAuthorizer authorizer,
            BiFunction<C, CallToolRequest, CallToolResult> delegate,
            C context,
            CallToolRequest request) {
        ToolAuthorizationDecision decision = authorizer.authorize(toolName);
        if (!decision.allowed()) {
            return denial(toolName, decision);
        }

        ToolFailureRecord.clear();
        try {
            return relabelIfStructured(toolName, delegate.apply(context, request));
        } catch (ToolInvocationException e) {
            // The tool body's own structured failure, thrown straight through — the shape a
            // hand-built specification produces, and the one the unit tests drive.
            return advisory(e.layer(), e.code(), e.getMessage());
        }
    }

    /**
     * The asynchronous rule: decide <strong>now</strong> (on the invoking thread), then hand back the
     * denial or the delegate's {@code Mono} with the labelling decoration — see the class javadoc.
     */
    static <C> Mono<CallToolResult> invokeAsync(
            String toolName,
            ToolCallAuthorizer authorizer,
            BiFunction<C, CallToolRequest, Mono<CallToolResult>> delegate,
            C context,
            CallToolRequest request) {
        ToolAuthorizationDecision decision = authorizer.authorize(toolName);
        if (!decision.allowed()) {
            return Mono.just(denial(toolName, decision));
        }

        ToolFailureRecord.clear();
        Mono<CallToolResult> result;
        try {
            result = delegate.apply(context, request);
        } catch (ToolInvocationException e) {
            return Mono.just(advisory(e.layer(), e.code(), e.getMessage()));
        }
        if (result == null) {
            // Exactly what the server would have received without the gate.
            return null;
        }
        return result
                .map(emitted -> relabelIfStructured(toolName, emitted))
                .onErrorResume(ToolInvocationException.class,
                        e -> Mono.just(advisory(e.layer(), e.code(), e.getMessage())));
    }

    /** The denial the caller sees: logged once, with its layer and stable code. */
    private static CallToolResult denial(String toolName, ToolAuthorizationDecision decision) {
        log.info("Tool call '{}' denied at {} ({})", toolName, decision.layer().label(), decision.code());
        return advisory(decision.layer(), decision.code(), decision.message());
    }

    /**
     * Restore the layer and code on an error result the tool body actually produced structurally.
     *
     * <h2>Why a catch is not enough</h2>
     * In the real invocation path the delegate is Spring AI's annotation-scanned handler, and it catches
     * whatever the {@code @McpTool} method throws, flattening it into a {@code CallToolResult} with
     * {@code isError} set and a plain-text message. The exception never reaches the gate, so a
     * <strong>target-gate denial arrived looking exactly like a transport fault</strong> — no layer, no
     * code — which is precisely the distinction the tool-gate exists to make. {@link ToolFailureRecord}
     * carries the detail across that seam; here it is put back on the result.
     *
     * <p>Strictly a labelling step: it only ever rewrites a result that is <em>already</em> an error, and
     * only when the tool body recorded structured detail for this very request. A missing record leaves
     * the delegate's result untouched. It can never turn an error into a success.
     */
    static CallToolResult relabelIfStructured(String toolName, CallToolResult result) {
        if (result == null || !Boolean.TRUE.equals(result.isError())) {
            ToolFailureRecord.clear();
            return result;
        }
        ToolInvocationException recorded = ToolFailureRecord.take();
        if (recorded == null) {
            return result;
        }
        if (log.isInfoEnabled()) {
            log.info("Tool call '{}' failed at {} ({})", toolName, recorded.layerLabel(), recorded.code());
        }
        return advisory(recorded.layer(), recorded.code(), recorded.getMessage());
    }

    /**
     * A structured, model-readable error: the layer and a stable code in {@code _meta}, and a safe message.
     */
    static CallToolResult advisory(ToolErrorLayer layer, String code, String message) {
        String label = layer == null ? null : layer.label();
        return new CallToolResult(
                List.of(new TextContent(null, message, null)),
                Boolean.TRUE,
                null,
                Map.of("layer", String.valueOf(label), "code", String.valueOf(code)));
    }
}
