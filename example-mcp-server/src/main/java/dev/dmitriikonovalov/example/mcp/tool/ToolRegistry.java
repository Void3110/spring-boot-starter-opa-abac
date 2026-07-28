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
 *
 * <h2>Order is part of the contract</h2>
 * {@link #all()} and {@link #names()} return <strong>declaration order</strong>, held in a separate
 * immutable list rather than read back out of the lookup map. {@code Map.copyOf} gives no ordering
 * guarantee at all — its iteration order is randomized <em>per JVM run</em> — so serving the roster from
 * the map would make the advertised tool order nondeterministic, and would break any caller that pairs
 * this list positionally with another. The roster pre-flight does exactly that: it sends one context per
 * tool and matches the returned booleans <em>by index</em>.
 */
public final class ToolRegistry {

    private final Map<String, ToolDescriptor> byName;
    private final List<ToolDescriptor> ordered;

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
        this.ordered = List.copyOf(index.values());
    }

    /** The descriptor for {@code toolName}, or empty when the tool was never declared. */
    public Optional<ToolDescriptor> find(String toolName) {
        return toolName == null ? Optional.empty() : Optional.ofNullable(byName.get(toolName));
    }

    /** Every declared tool, in declaration order — stable within and across runs. */
    public List<ToolDescriptor> all() {
        return ordered;
    }

    /** The declared tool names, in declaration order — positionally aligned with {@link #all()}. */
    public List<String> names() {
        return ordered.stream().map(ToolDescriptor::name).toList();
    }
}
