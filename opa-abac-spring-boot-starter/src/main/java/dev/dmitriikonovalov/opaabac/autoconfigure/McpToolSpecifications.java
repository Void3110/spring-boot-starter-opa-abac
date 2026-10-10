package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallGate;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * What the tool-gate's post-processor and startup verifier agree a tool specification <em>is</em>.
 *
 * <p>The rule is structural on purpose: any element with a public no-arg {@code tool()} accessor
 * returning {@link McpSchema.Tool} carries a tool — every MCP SDK specification record has one — so a
 * specification type this module has never heard of (a future SDK's fifth) is recognized as tool-bearing
 * and refused, rather than passed through ungated because its name was not on a list.
 */
final class McpToolSpecifications {

    /** The one type part 0 wraps; every other tool-bearing type fails startup (ADR 0036 §3). */
    static final String SUPPORTED = "McpServerFeatures.SyncToolSpecification";

    private McpToolSpecifications() {
    }

    /** True when {@code element} exposes a public no-arg {@code McpSchema.Tool tool()} accessor. */
    static boolean isToolBearing(Object element) {
        if (element == null) {
            return false;
        }
        try {
            Method accessor = element.getClass().getMethod("tool");
            return Modifier.isPublic(accessor.getModifiers())
                    && McpSchema.Tool.class.isAssignableFrom(accessor.getReturnType());
        } catch (NoSuchMethodException _) {
            return false;
        }
    }

    /** True when {@code element} is a specification whose handler is one of this module's gates. */
    static boolean isGated(Object element) {
        return element instanceof SyncToolSpecification sync && sync.callHandler() instanceof ToolCallGate;
    }
}
