package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * The tool-gate's startup guard: <strong>one</strong> {@link SmartInitializingSingleton} running its steps
 * in a fixed order inside a single callback (ADR 0036 §13).
 *
 * <ol>
 *   <li><strong>Not a reactive application.</strong> Spring WebFlux is out of scope for v1 (ADR 0036 §4): the
 *       gate reads the caller from thread-bound state, which a reactive server does not keep on the thread
 *       that invokes a handler. Detected by {@code OpaMcpAutoConfiguration}'s nested
 *       {@code @ConditionalOnWebApplication(type = REACTIVE)} configuration, which contributes a
 *       {@link ReactiveWebApplication} marker; its presence fails startup first, before any other step can
 *       report a less relevant cause.</li>
 *   <li><strong>Prerequisites.</strong> No {@link OpaClient} ⇒ fail, naming {@code opa.abac.enabled} — also
 *       when the starter was switched off with {@code opa.abac.allow-ungated-methods=true}: that
 *       acknowledgment covers {@code @OpaPreAuthorize} methods, never an ungated tool surface (§12). No
 *       {@link PrincipalCeilingSupplier} ⇒ fail, naming the SPI (there is no default).</li>
 *   <li><strong>Eager list resolution.</strong> Every <em>singleton</em> {@code List} bean is resolved, so
 *       under {@code spring.main.lazy-initialization=true} each specification list is still created — and
 *       therefore post-processed: wrapped, or refused as an unsupported type. Prototype- and scope-bound
 *       lists are never touched ({@code includeNonSingletons=false}), and a scoped proxy is skipped by its
 *       {@code scopedTarget.} sibling. Then the result is <strong>asserted</strong>: every tool-bearing
 *       element of every resolved list must carry the gate — the backstop for a list that never passed the
 *       post-processor (one added with {@code registerSingleton} is returned by the lookup but never
 *       post-processed).</li>
 *   <li><strong>Declarations ⇔ what was wrapped</strong> (ADR 0036 §9). The names the gate post-processor
 *       gated (the union across every list) are compared with the {@link ToolRegistry}, both directions:
 *       advertised-but-undeclared ⇒ fail (a tool with nothing to authorize against); declared-but-never-wrapped
 *       ⇒ fail (the registry describes a tool the server does not gate — one registered with {@code addTool}
 *       or a customizer, or a phantom). Both sorted lists are in the message. No {@code ToolRegistry} bean ⇒
 *       fail, naming it. An empty registry and no tools ⇒ starts.</li>
 * </ol>
 *
 * <p>The steps share one callback because Spring runs separate {@code SmartInitializingSingleton}
 * callbacks in bean-registration order ({@code @Order} does not apply): a later step living in its own bean
 * could run before the lists were resolved and judge a valid context. The callback itself is eager: Boot's
 * lazy-initialization post-processor exempts {@code SmartInitializingSingleton} beans.
 */
final class McpStartupVerifier implements SmartInitializingSingleton {

    /**
     * Present only in a reactive web application — contributed by a configuration conditional on
     * {@code @ConditionalOnWebApplication(type = REACTIVE)}, so the detection is Boot's own.
     */
    enum ReactiveWebApplication {
        DETECTED
    }

    private final ConfigurableListableBeanFactory beanFactory;
    private final ObjectProvider<ReactiveWebApplication> reactive;
    private final ObjectProvider<OpaClient> opaClient;
    private final ObjectProvider<PrincipalCeilingSupplier> ceilingSupplier;
    private final ObjectProvider<ToolRegistry> registry;
    private final McpToolGatePostProcessor gate;

    McpStartupVerifier(
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<ReactiveWebApplication> reactive,
            ObjectProvider<OpaClient> opaClient,
            ObjectProvider<PrincipalCeilingSupplier> ceilingSupplier,
            ObjectProvider<ToolRegistry> registry,
            McpToolGatePostProcessor gate) {
        this.beanFactory = beanFactory;
        this.reactive = reactive;
        this.opaClient = opaClient;
        this.ceilingSupplier = ceilingSupplier;
        this.registry = registry;
        this.gate = gate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verifyNotReactive();
        verifyPrerequisites();
        resolveAndAssertEveryListIsGated();
        verifyDeclarationsMatchWhatWasGated();
    }

    /** Step 0 — a reactive (WebFlux) application is refused: the gate could not see the caller there. */
    void verifyNotReactive() {
        if (reactive.getIfAvailable() != null) {
            throw new IllegalStateException(
                    "opa-abac-mcp does not support a reactive (Spring WebFlux) web application — it is not "
                            + "supported in v1 (ADR 0036 §4). The tool-gate reads the caller from the thread "
                            + "that invokes a tool handler, which a reactive server does not keep, so it could "
                            + "not decide any call. Run the MCP server on Spring MVC (servlet).");
        }
    }

    /** Step 1 — the beans the gate cannot decide without, each named with its fix. */
    void verifyPrerequisites() {
        if (opaClient.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "opa-abac-mcp is on the classpath next to Spring AI's MCP server, but there is no OpaClient "
                            + "bean, so the tool-gate cannot decide and no tool may be served. Turn the starter on "
                            + "(opa.abac.enabled is false, or its auto-configuration was excluded) or define an "
                            + "OpaClient bean. opa.abac.allow-ungated-methods does not apply to MCP tools: it "
                            + "acknowledges ungated @OpaPreAuthorize methods, never an ungated tool surface.");
        }
        if (ceilingSupplier.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "opa-abac-mcp needs a PrincipalCeilingSupplier bean — the principal's type-level ceiling, "
                            + "the human half of the tool-gate's decision — and ships no default. Define one "
                            + "(see its javadoc for the contract).");
        }
    }

    /** Step 2 — resolve every singleton list (post-processing it), then assert no tool escaped the gate. */
    void resolveAndAssertEveryListIsGated() {
        for (String name : singletonListBeanNames()) {
            Object list = beanFactory.getBean(name);
            if (!(list instanceof List<?> elements)) {
                continue;
            }
            for (Object element : elements) {
                if (McpToolSpecifications.isToolBearing(element) && !McpToolSpecifications.isGated(element)) {
                    throw new IllegalStateException("Bean '" + name + "' serves a tool specification ("
                            + element.getClass().getName() + ") without the opa-abac-mcp tool-gate — it never "
                            + "passed the gate's post-processor (a list added with registerSingleton, or one "
                            + "replaced after post-processing). Supported registrations are singleton List "
                            + "bean definitions of " + McpToolSpecifications.SUPPORTED + ".");
                }
            }
        }
    }

    /**
     * Step 3 — the registry against the names the gate actually gated. Runs after step 2 in the same
     * callback, so every lazily-defined list has been resolved — wrapped and counted, never missed.
     */
    void verifyDeclarationsMatchWhatWasGated() {
        ToolRegistry declarations = registry.getIfAvailable();
        if (declarations == null) {
            throw new IllegalStateException(
                    "opa-abac-mcp needs a ToolRegistry bean declaring every tool's action, category, target type "
                            + "and risk tags. Without one the gate has nothing to authorize against — every call "
                            + "would be denied as tool-undeclared — so startup stops here. Define one (an empty "
                            + "registry is valid for a server with no tools).");
        }
        Set<String> gated = gate.gatedToolNames();
        Set<String> declared = new TreeSet<>(declarations.names());

        Set<String> undeclared = new TreeSet<>(gated);
        undeclared.removeAll(declared);
        Set<String> neverGated = new TreeSet<>(declared);
        neverGated.removeAll(gated);
        if (!undeclared.isEmpty() || !neverGated.isEmpty()) {
            throw new IllegalStateException("The ToolRegistry does not match the tools the gate wrapped. "
                    + "Advertised but undeclared (a tool with no action/category/risk to authorize against): "
                    + undeclared + ". Declared but never wrapped (not in any singleton specification-list bean — "
                    + "e.g. registered with addTool or a server customizer, which the gate never sees): "
                    + neverGated + ". Declared: " + declared + ". Wrapped: " + gated + ".");
        }
    }

    /** Singleton {@code List} beans, minus scoped targets and scoped proxies — never instantiating others. */
    private List<String> singletonListBeanNames() {
        List<String> names = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(List.class, false, false)) {
            if (ScopedProxyUtils.isScopedTarget(name) || McpToolSpecifications.isScopedProxy(beanFactory, name)) {
                continue;
            }
            names.add(name);
        }
        return names;
    }
}
