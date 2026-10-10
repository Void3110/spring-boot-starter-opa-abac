package dev.dmitriikonovalov.opaabac.mcp.authz;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

/**
 * The tool-gate on a stateful <strong>asynchronous</strong> tool specification
 * ({@code spring.ai.mcp.server.type=ASYNC}).
 *
 * <p>It decides when its handler is <em>invoked</em> — on the transport's calling thread, where the
 * caller's identity is bound — and only then returns a {@code Mono}: the denial, or the delegate's own
 * {@code Mono} decorated for labelling. The decision is never taken inside the {@code Mono}, so a
 * resubscription never calls the authorizer again (the rule is {@link ToolGateInvocation}'s, shared by all
 * four wrappers).
 */
public class AsyncToolCallGate
        implements BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> {

    private final String toolName;
    private final BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> delegate;
    private final ToolCallAuthorizer authorizer;

    public AsyncToolCallGate(
            String toolName,
            BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> delegate,
            ToolCallAuthorizer authorizer) {
        this.toolName = toolName;
        this.delegate = delegate;
        this.authorizer = authorizer;
    }

    /** Wrap one specification's handler, keeping its advertised {@code tool} untouched. */
    public static AsyncToolSpecification gate(AsyncToolSpecification specification, ToolCallAuthorizer authorizer) {
        return new AsyncToolSpecification(
                specification.tool(),
                new AsyncToolCallGate(specification.tool().name(), specification.callHandler(), authorizer));
    }

    @Override
    public Mono<CallToolResult> apply(McpAsyncServerExchange exchange, CallToolRequest request) {
        return ToolGateInvocation.invokeAsync(toolName, authorizer, delegate, exchange, request);
    }
}
