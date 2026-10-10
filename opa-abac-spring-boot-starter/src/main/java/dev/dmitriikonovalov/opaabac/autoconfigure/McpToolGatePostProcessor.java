package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Installs the tool-gate on every tool the MCP server is handed: every {@code List} bean is inspected,
 * <strong>element by element</strong>, as it is created.
 *
 * <ul>
 *   <li>an element of any of the four SDK tool-specification types — sync or async, stateful or
 *       stateless — is wrapped with its type's gate (one already gated <em>by the context's authorizer for its
 *       own tool name</em> is kept, never wrapped twice; a gate bound to another name or authorizer is wrapped
 *       again);</li>
 *   <li>any other <em>tool-bearing</em> element ({@link McpToolSpecifications#isToolBearing}) — a type the
 *       module does not know, e.g. one a future SDK adds — <strong>fails startup</strong>, naming its type:
 *       the module never serves a type it does not gate. Every element is checked, not only the first;</li>
 *   <li>a list with no tool-bearing element ({@code List<String>}, resource specifications) is returned
 *       untouched.</li>
 * </ul>
 *
 * <p>A scoped proxy (a bean with a {@code scopedTarget.} sibling) is passed through without being
 * iterated: iterating it outside a request would resolve its target and fail startup, and a scope-bound
 * list is an unsupported registration either way.
 *
 * <p>The authorizer is fetched lazily, on the first list that carries a tool — so this post-processor
 * forces nothing at registration time.
 *
 * <p>It <strong>records the name of every tool it gated</strong> (wrapped, or found already gated), across
 * all lists — the set {@code McpStartupVerifier} checks against the {@code ToolRegistry} in both directions.
 * Only what passed through here is in the set: a specification registered any other way ({@code addTool},
 * a server customizer) is never wrapped, never counted, and declaring it fails startup.
 *
 * <p>A post-processor only sees beans that get created: under lazy initialization a list nothing injects
 * would escape it. {@code McpStartupVerifier} closes that by resolving every singleton list at startup and
 * asserting that every tool-bearing element ended up gated.
 */
final class McpToolGatePostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(McpToolGatePostProcessor.class);

    private final ObjectProvider<ToolCallAuthorizer> authorizerProvider;
    private final Set<String> gatedToolNames = new ConcurrentSkipListSet<>();
    /** The authorizer every gate this post-processor installs is bound to — resolved once, on first use. */
    private final AtomicReference<ToolCallAuthorizer> installedAuthorizer = new AtomicReference<>();
    private ConfigurableListableBeanFactory beanFactory;

    McpToolGatePostProcessor(ObjectProvider<ToolCallAuthorizer> authorizerProvider) {
        this.authorizerProvider = authorizerProvider;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory instanceof ConfigurableListableBeanFactory listable ? listable : null;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof List<?> list) || isScopedProxy(beanName)) {
            return bean;
        }
        boolean carriesTools = false;
        for (Object element : list) {
            if (McpToolSpecifications.isSupported(element)) {
                carriesTools = true;
            } else if (McpToolSpecifications.isToolBearing(element)) {
                throw new IllegalStateException("Bean '" + beanName + "' holds a tool specification of type "
                        + element.getClass().getName() + ", which the opa-abac-mcp tool-gate cannot wrap "
                        + "(supported: " + McpToolSpecifications.SUPPORTED + "). An MCP server never serves a "
                        + "tool without the gate, so startup stops here.");
            }
        }
        if (!carriesTools) {
            return bean;
        }

        ToolCallAuthorizer authorizer = installedAuthorizer();
        List<Object> gated = new ArrayList<>(list.size());
        int wrapped = 0;
        for (Object element : list) {
            if (!McpToolSpecifications.isSupported(element)) {
                gated.add(element);
                continue;
            }
            String toolName = McpToolSpecifications.toolName(element);
            gatedToolNames.add(toolName);
            if (McpToolSpecifications.hasGateHandler(element)
                    && !McpToolSpecifications.isGated(element, authorizer)) {
                log.warn("Tool '{}' in '{}' carries a tool-gate bound to another tool's name or another authorizer "
                        + "— wrapped again with the application's gate, which decides first", toolName, beanName);
            }
            Object withGate = McpToolSpecifications.gate(element, authorizer);
            if (withGate != element) {
                wrapped++;
            }
            gated.add(withGate);
        }
        log.info("Tool-gate installed on {} tool specification(s) in '{}'", wrapped, beanName);
        return gated;
    }

    /**
     * True when {@code element} is gated by the application's authorizer for its own tool name — the same test
     * this post-processor applies before keeping an element as is, so the verifier and the post-processor
     * never disagree on what "gated" means.
     */
    boolean isGated(Object element) {
        ToolCallAuthorizer installed = installedAuthorizer.get();
        return McpToolSpecifications.isGated(
                element, installed != null ? installed : authorizerProvider.getIfAvailable());
    }

    /**
     * The one authorizer instance every installed gate is bound to, resolved on the first tool list and kept —
     * so "gated" means the same instance here and in the verifier even for a non-singleton authorizer bean.
     */
    private ToolCallAuthorizer installedAuthorizer() {
        return installedAuthorizer.updateAndGet(
                installed -> installed != null ? installed : authorizerProvider.getObject());
    }

    /** Every tool name gated so far, sorted — a snapshot; the startup verifier compares it with the registry. */
    Set<String> gatedToolNames() {
        return new TreeSet<>(gatedToolNames);
    }

    /** A scoped proxy's target lives under {@code scopedTarget.<name>}; never iterate the proxy. */
    private boolean isScopedProxy(String beanName) {
        return McpToolSpecifications.isScopedProxy(beanFactory, beanName);
    }
}
