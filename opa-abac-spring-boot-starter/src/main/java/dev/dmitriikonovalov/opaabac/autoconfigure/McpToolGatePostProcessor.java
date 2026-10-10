package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallGate;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
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
 *   <li>a {@link SyncToolSpecification} is wrapped with {@link ToolCallGate} (one already gated is kept,
 *       never wrapped twice);</li>
 *   <li>any other <em>tool-bearing</em> element ({@link McpToolSpecifications#isToolBearing}) — the three
 *       other SDK specification types included, until they are supported — <strong>fails startup</strong>,
 *       naming its type: the module never serves a type it does not gate;</li>
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
            if (element instanceof SyncToolSpecification) {
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

        ToolCallAuthorizer authorizer = authorizerProvider.getObject();
        List<Object> gated = new ArrayList<>(list.size());
        int wrapped = 0;
        for (Object element : list) {
            if (element instanceof SyncToolSpecification specification) {
                gatedToolNames.add(specification.tool().name());
                if (!(specification.callHandler() instanceof ToolCallGate)) {
                    gated.add(ToolCallGate.gate(specification, authorizer));
                    wrapped++;
                    continue;
                }
            }
            gated.add(element);
        }
        log.info("Tool-gate installed on {} tool specification(s) in '{}'", wrapped, beanName);
        return gated;
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
