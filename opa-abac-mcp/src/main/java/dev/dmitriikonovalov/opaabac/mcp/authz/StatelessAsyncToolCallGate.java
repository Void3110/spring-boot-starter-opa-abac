package dev.dmitriikonovalov.opaabac.mcp.authz;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

/**
 * The tool-gate on a <strong>stateless asynchronous</strong> tool specification
 * ({@code spring.ai.mcp.server.type=ASYNC} + {@code protocol=STATELESS}): decided when the handler is
 * invoked, on the calling thread, before any {@code Mono} exists — then the denial, or the delegate's
 * {@code Mono} decorated for labelling ({@link ToolGateInvocation}). A resubscription never re-decides.
 */
public class StatelessAsyncToolCallGate
        implements BiFunction<McpTransportContext, CallToolRequest, Mono<CallToolResult>> {

    private final String toolName;
    private final BiFunction<McpTransportContext, CallToolRequest, Mono<CallToolResult>> delegate;
    private final ToolCallAuthorizer authorizer;

    public StatelessAsyncToolCallGate(
            String toolName,
            BiFunction<McpTransportContext, CallToolRequest, Mono<CallToolResult>> delegate,
            ToolCallAuthorizer authorizer) {
        this.toolName = toolName;
        this.delegate = delegate;
        this.authorizer = authorizer;
    }

    /** Wrap one specification's handler, keeping its advertised {@code tool} untouched. */
    public static AsyncToolSpecification gate(AsyncToolSpecification specification, ToolCallAuthorizer authorizer) {
        return new AsyncToolSpecification(
                specification.tool(),
                new StatelessAsyncToolCallGate(
                        specification.tool().name(), specification.callHandler(), authorizer));
    }

    @Override
    public Mono<CallToolResult> apply(McpTransportContext transportContext, CallToolRequest request) {
        return ToolGateInvocation.invokeAsync(toolName, authorizer, delegate, transportContext, request);
    }
}
