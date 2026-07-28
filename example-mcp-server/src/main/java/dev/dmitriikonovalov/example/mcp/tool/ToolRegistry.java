package dev.dmitriikonovalov.example.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The declared tools, indexed by name — the single place the tool-gate looks up what a tool <em>is</em>.
 *
 * <p>A lookup of a name that was never declared returns {@link Optional#empty()}, never a permissive
 * default: an undeclared tool has no attributes, so the gate has nothing to authorize and denies. The
 * registry is built once at startup and is immutable thereafter.
 *
 * <p>Duplicate names are rejected at construction. Two tools sharing a name would make the gate's answer
 * depend on map ordering, which is exactly the kind of ambiguity a policy decision must not have.
 */
public final class ToolRegistry {

    private final Map<String, ToolDescriptor> byName;

    public ToolRegistry(List<ToolDescriptor> descriptors) {
        Map<String, ToolDescriptor> index = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            ToolDescriptor previous = index.put(descriptor.name(), descriptor);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate tool declaration for '" + descriptor.name() + "'");
            }
        }
        this.byName = Map.copyOf(index);
    }

    /** The descriptor for {@code toolName}, or empty when the tool was never declared. */
    public Optional<ToolDescriptor> find(String toolName) {
        return toolName == null ? Optional.empty() : Optional.ofNullable(byName.get(toolName));
    }

    /** Every declared tool, in declaration order. */
    public List<ToolDescriptor> all() {
        return List.copyOf(byName.values());
    }

    /** The declared tool names. */
    public java.util.Set<String> names() {
        return byName.keySet();
    }
}
