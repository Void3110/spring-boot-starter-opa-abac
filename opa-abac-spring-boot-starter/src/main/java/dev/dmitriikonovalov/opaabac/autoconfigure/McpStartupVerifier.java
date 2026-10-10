package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import java.util.ArrayList;
import java.util.List;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * The tool-gate's startup guard: <strong>one</strong> {@link SmartInitializingSingleton} running its steps
 * in a fixed order inside a single callback (ADR 0036 §13).
 *
 * <ol>
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
 * </ol>
 *
 * <p>The steps share one callback because Spring runs separate {@code SmartInitializingSingleton}
 * callbacks in bean-registration order ({@code @Order} does not apply): a later step living in its own bean
 * could run before the lists were resolved and judge a valid context. The callback itself is eager: Boot's
 * lazy-initialization post-processor exempts {@code SmartInitializingSingleton} beans.
 */
final class McpStartupVerifier implements SmartInitializingSingleton {

    private final ConfigurableListableBeanFactory beanFactory;
    private final ObjectProvider<OpaClient> opaClient;
    private final ObjectProvider<PrincipalCeilingSupplier> ceilingSupplier;

    McpStartupVerifier(
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<OpaClient> opaClient,
            ObjectProvider<PrincipalCeilingSupplier> ceilingSupplier) {
        this.beanFactory = beanFactory;
        this.opaClient = opaClient;
        this.ceilingSupplier = ceilingSupplier;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verifyPrerequisites();
        resolveAndAssertEveryListIsGated();
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

    /** Singleton {@code List} beans, minus scoped targets and scoped proxies — never instantiating others. */
    private List<String> singletonListBeanNames() {
        List<String> names = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(List.class, false, false)) {
            if (ScopedProxyUtils.isScopedTarget(name)
                    || beanFactory.containsBeanDefinition(ScopedProxyUtils.getTargetBeanName(name))) {
                continue;
            }
            names.add(name);
        }
        return names;
    }
}
