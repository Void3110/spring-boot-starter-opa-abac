package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.mcp.authz.AsyncToolCallGate;
import dev.dmitriikonovalov.opaabac.mcp.authz.StatelessAsyncToolCallGate;
import dev.dmitriikonovalov.opaabac.mcp.authz.StatelessToolCallGate;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallGate;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * What the tool-gate's post-processor and startup verifier agree a tool specification <em>is</em>.
 *
 * <p>The rule is structural on purpose: any element with a public no-arg {@code tool()} accessor
 * returning {@link McpSchema.Tool} carries a tool — every MCP SDK specification record has one — so a
 * specification type this module has never heard of (a future SDK's fifth) is recognized as tool-bearing
 * and refused, rather than passed through ungated because its name was not on a list.
 *
 * <p>The four SDK specification types are <em>supported</em>: each is wrapped by its own gate
 * ({@link ToolCallGate}, {@link AsyncToolCallGate}, {@link StatelessToolCallGate},
 * {@link StatelessAsyncToolCallGate}), and an element of one of them counts as gated only when its handler
 * is <em>that type's</em> gate, bound to the element's own tool name and to the application's
 * {@link ToolCallAuthorizer}. A hand-built gate bound to another tool's name, or to another authorizer (one
 * constructed inline skips the configured agent-clients list), is not trusted: it is wrapped again.
 */
final class McpToolSpecifications {

    /** The four types the gate wraps; every other tool-bearing type fails startup (ADR 0036 §3). */
    static final String SUPPORTED = "McpServerFeatures.SyncToolSpecification, "
            + "McpServerFeatures.AsyncToolSpecification, McpStatelessServerFeatures.SyncToolSpecification, "
            + "McpStatelessServerFeatures.AsyncToolSpecification";

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

    /**
     * True when {@code beanName} is a <em>scoped proxy</em>: its definition decorates the
     * {@code scopedTarget.<name>} definition, exactly as {@link ScopedProxyUtils} registers one. Judged by the
     * definition's structure, not by the name alone — an unrelated bean that merely happens to be called
     * {@code scopedTarget.<name>} must not exempt a singleton tool list from the gate.
     */
    static boolean isScopedProxy(ConfigurableListableBeanFactory beanFactory, String beanName) {
        if (beanFactory == null || beanName == null) {
            return false;
        }
        String targetName = ScopedProxyUtils.getTargetBeanName(beanName);
        if (!beanFactory.containsBeanDefinition(targetName) || !beanFactory.containsBeanDefinition(beanName)) {
            return false;
        }
        if (!(beanFactory.getMergedBeanDefinition(beanName) instanceof RootBeanDefinition proxyDefinition)) {
            return false;
        }
        BeanDefinitionHolder decorated = proxyDefinition.getDecoratedDefinition();
        return decorated != null && targetName.equals(decorated.getBeanName());
    }

    /** True when {@code element} is one of the four SDK tool-specification types the gate wraps. */
    static boolean isSupported(Object element) {
        return element instanceof McpServerFeatures.SyncToolSpecification
                || element instanceof McpServerFeatures.AsyncToolSpecification
                || element instanceof McpStatelessServerFeatures.SyncToolSpecification
                || element instanceof McpStatelessServerFeatures.AsyncToolSpecification;
    }

    /**
     * True when {@code element} is a supported specification whose handler is <em>its type's</em> gate, however
     * bound — the post-processor's cue to WARN before wrapping a mis-bound gate again.
     */
    static boolean hasGateHandler(Object element) {
        return switch (element) {
            case McpServerFeatures.SyncToolSpecification(_, var handler) -> handler instanceof ToolCallGate;
            case McpServerFeatures.AsyncToolSpecification(_, var handler) -> handler instanceof AsyncToolCallGate;
            case McpStatelessServerFeatures.SyncToolSpecification(_, var handler) ->
                    handler instanceof StatelessToolCallGate;
            case McpStatelessServerFeatures.AsyncToolSpecification(_, var handler) ->
                    handler instanceof StatelessAsyncToolCallGate;
            case null, default -> false;
        };
    }

    /**
     * True when {@code element} is a supported specification whose handler is <em>its type's</em> gate, bound
     * to the element's own tool name and to {@code authorizer} (the same instance). A {@code null} authorizer
     * gates nothing.
     */
    static boolean isGated(Object element, ToolCallAuthorizer authorizer) {
        if (authorizer == null) {
            return false;
        }
        return switch (element) {
            case McpServerFeatures.SyncToolSpecification(var tool, var handler) ->
                    handler instanceof ToolCallGate g && g.isBoundTo(tool.name(), authorizer);
            case McpServerFeatures.AsyncToolSpecification(var tool, var handler) ->
                    handler instanceof AsyncToolCallGate g && g.isBoundTo(tool.name(), authorizer);
            case McpStatelessServerFeatures.SyncToolSpecification(var tool, var handler) ->
                    handler instanceof StatelessToolCallGate g && g.isBoundTo(tool.name(), authorizer);
            case McpStatelessServerFeatures.AsyncToolSpecification(var tool, var handler) ->
                    handler instanceof StatelessAsyncToolCallGate g && g.isBoundTo(tool.name(), authorizer);
            case null, default -> false;
        };
    }

    /** The advertised tool name of a supported specification. */
    static String toolName(Object element) {
        return switch (element) {
            case McpServerFeatures.SyncToolSpecification s -> s.tool().name();
            case McpServerFeatures.AsyncToolSpecification s -> s.tool().name();
            case McpStatelessServerFeatures.SyncToolSpecification s -> s.tool().name();
            case McpStatelessServerFeatures.AsyncToolSpecification s -> s.tool().name();
            default -> throw new IllegalArgumentException("not a supported tool specification: " + element);
        };
    }

    /**
     * {@code element} with its type's gate installed — the element itself when it is already gated by this
     * {@code authorizer} for its own tool name (never wrapped twice); a gate bound otherwise is wrapped again.
     * Only ever called with a supported element.
     */
    static Object gate(Object element, ToolCallAuthorizer authorizer) {
        if (isGated(element, authorizer)) {
            return element;
        }
        return switch (element) {
            case McpServerFeatures.SyncToolSpecification s -> ToolCallGate.gate(s, authorizer);
            case McpServerFeatures.AsyncToolSpecification s -> AsyncToolCallGate.gate(s, authorizer);
            case McpStatelessServerFeatures.SyncToolSpecification s -> StatelessToolCallGate.gate(s, authorizer);
            case McpStatelessServerFeatures.AsyncToolSpecification s -> StatelessAsyncToolCallGate.gate(s, authorizer);
            default -> throw new IllegalArgumentException("not a supported tool specification: " + element);
        };
    }
}
