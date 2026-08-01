package dev.dmitriikonovalov.example.mcp.tool;

import java.util.Optional;

/**
 * SPI for deriving a {@link ToolDescriptor} for a tool that did <strong>not</strong> declare one.
 *
 * <h2>Contract: most restrictive on ambiguity</h2>
 * An implementation must return the <em>narrowest</em> descriptor consistent with what it can determine —
 * the highest risk tags, the most privileged category — and {@link Optional#empty()} when it cannot
 * determine anything at all. It must never guess in the permissive direction. A caller treats an empty
 * result as "unclassifiable", which denies.
 *
 * <h2>Why no implementation ships</h2>
 * This interface is deliberately <strong>contract-only</strong> in this slice. Its single named consumer
 * is {@code ToolCallAuthorizer} (T4), which would consult it only for an <em>undeclared</em> tool — and
 * {@code ToolRegistryValidator} makes that state unreachable by failing startup when a registered
 * {@code @McpTool} has no declaration. An implementation would therefore be an unconsumed seam with no
 * test able to distinguish it from a stub, so the contract ships and the implementation waits for a real
 * consumer (ADR 0028, considered-and-rejected).
 */
@FunctionalInterface
public interface ToolCallClassifier {

    /**
     * Classify an undeclared tool, most-restrictively.
     *
     * @param toolName the advertised MCP tool name
     * @return the narrowest descriptor that can be justified, or empty when the tool cannot be classified
     */
    Optional<ToolDescriptor> classify(String toolName);
}
