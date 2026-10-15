package dev.dmitriikonovalov.opaabac.mcp.authz;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Objects;
import java.util.function.BiFunction;

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
 *
 * <h2>The other three specification types</h2>
 * This wraps the stateful synchronous type. {@link AsyncToolCallGate}, {@link StatelessToolCallGate} and
 * {@link StatelessAsyncToolCallGate} wrap the other three under the same rule
 * ({@link ToolGateInvocation}): decide when the handler is invoked, on the invoking thread.
 */
public final class ToolCallGate
        implements BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> {

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

    /**
     * True when this gate decides for {@code toolName} with exactly {@code authorizer} (the same instance).
     * The starter keeps an already-gated specification as is only when its gate is bound to the
     * specification's own tool name and the application's authorizer; any other gate is wrapped again — a
     * second gate can only narrow.
     */
    public boolean isBoundTo(String toolName, ToolCallAuthorizer authorizer) {
        return Objects.equals(this.toolName, toolName) && this.authorizer == authorizer;
    }

    @Override
    public CallToolResult apply(McpSyncServerExchange exchange, CallToolRequest request) {
        return ToolGateInvocation.invokeSync(toolName, authorizer, delegate, exchange, request);
    }
}
