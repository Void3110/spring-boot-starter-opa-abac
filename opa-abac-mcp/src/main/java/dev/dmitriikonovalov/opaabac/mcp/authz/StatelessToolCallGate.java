package dev.dmitriikonovalov.opaabac.mcp.authz;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.BiFunction;

/**
 * The tool-gate on a <strong>stateless synchronous</strong> tool specification
 * ({@code spring.ai.mcp.server.protocol=STATELESS}): the same rule as {@link ToolCallGate}
 * ({@link ToolGateInvocation}) with the stateless handler's {@link McpTransportContext} first argument.
 *
 * <p>The identity is still the thread-bound one — read where the handler is invoked. The transport context
 * is passed through to the tool body untouched; carrying the identity in it is ADR 0036 §4's planned
 * successor, not this class's job.
 */
public class StatelessToolCallGate
        implements BiFunction<McpTransportContext, CallToolRequest, CallToolResult> {

    private final String toolName;
    private final BiFunction<McpTransportContext, CallToolRequest, CallToolResult> delegate;
    private final ToolCallAuthorizer authorizer;

    public StatelessToolCallGate(
            String toolName,
            BiFunction<McpTransportContext, CallToolRequest, CallToolResult> delegate,
            ToolCallAuthorizer authorizer) {
        this.toolName = toolName;
        this.delegate = delegate;
        this.authorizer = authorizer;
    }

    /** Wrap one specification's handler, keeping its advertised {@code tool} untouched. */
    public static SyncToolSpecification gate(SyncToolSpecification specification, ToolCallAuthorizer authorizer) {
        return new SyncToolSpecification(
                specification.tool(),
                new StatelessToolCallGate(specification.tool().name(), specification.callHandler(), authorizer));
    }

    @Override
    public CallToolResult apply(McpTransportContext transportContext, CallToolRequest request) {
        return ToolGateInvocation.invokeSync(toolName, authorizer, delegate, transportContext, request);
    }
}
