package dev.dmitriikonovalov.example.mcp.authz;

import dev.dmitriikonovalov.example.mcp.tool.ToolErrorLayer;
import dev.dmitriikonovalov.example.mcp.tool.ToolInvocationException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a tool's call handler so the tool-gate runs <strong>before</strong> the tool body, and a denial
 * short-circuits: the body never executes, no target is resolved, and no downstream request is made.
 *
 * <h2>Why the handler, and not an AOP aspect</h2>
 * The decomposition called this an aspect. Wrapping the {@link SyncToolSpecification}'s
 * {@code callHandler} is strictly stronger, and the difference matters for a gate whose entire purpose
 * is to be unbypassable. The handler <em>is</em> what the MCP server invokes for a {@code tools/call},
 * so nothing can route around it. A Spring AOP aspect would only intercept calls made through the
 * bean's proxy — and the annotation scanner builds its callbacks from the bean it was handed, so
 * whether the gate sat in the invocation path at all would depend on proxy-unwrapping behaviour we do
 * not control and that could change under us in a minor upgrade. A gate that is "probably in the path"
 * is not a gate.
 *
 * <h2>The denial is an advisory result, not an exception</h2>
 * A deny returns a {@link CallToolResult} with {@code isError} set and structured content naming the
 * denying layer and a stable code. That is the protocol's own way of telling a model something went
 * wrong in a way it can act on — pick another tool, ask the human to escalate — rather than a transport
 * fault it can only retry. Never a stack trace, never a silent empty result.
 */
public class ToolCallGate
        implements BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> {

    private static final Logger log = LoggerFactory.getLogger(ToolCallGate.class);

    private final String toolName;
    private final BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> delegate;
    private final ToolCallAuthorizer authorizer;

    public ToolCallGate(
            String toolName,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> delegate,
            ToolCallAuthorizer authorizer) {
        this.toolName = toolName;
        this.delegate = delegate;
        this.authorizer = authorizer;
    }

    /** Wrap one specification's handler, keeping its advertised {@code tool} untouched. */
    public static SyncToolSpecification gate(
            SyncToolSpecification specification, ToolCallAuthorizer authorizer) {
        return new SyncToolSpecification(
                specification.tool(),
                new ToolCallGate(
                        specification.tool().name(), specification.callHandler(), authorizer));
    }

    @Override
    public CallToolResult apply(McpSyncServerExchange exchange, CallToolRequest request) {
        ToolAuthorizationDecision decision = authorizer.authorize(toolName);
        if (!decision.allowed()) {
            log.info("Tool call '{}' denied at {} ({})",
                    toolName, decision.layer().label(), decision.code());
            return advisory(decision.layer(), decision.code(), decision.message());
        }

        try {
            return delegate.apply(exchange, request);
        } catch (ToolInvocationException e) {
            // The tool body's own structured failure — including the target-gate's translated 403.
            return advisory(e.layer(), e.code(), e.getMessage());
        }
    }

    /** A structured, model-readable error: the layer, a stable code, and a safe message. */
    private static CallToolResult advisory(ToolErrorLayer layer, String code, String message) {
        String label = layer == null ? null : layer.label();
        return new CallToolResult(
                List.of(new TextContent(null, message, null)),
                Boolean.TRUE,
                null,
                Map.of("layer", String.valueOf(label), "code", String.valueOf(code)));
    }
}
