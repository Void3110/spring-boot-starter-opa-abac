package dev.dmitriikonovalov.example.mcp.tool;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Cross-checks the {@code @McpTool} methods this server actually advertises against the
 * {@link ToolRegistry} declarations, and <strong>fails startup</strong> on any mismatch.
 *
 * <p>This is where the "unclassifiable tool is never exposed" rule is enforced for real. Validating the
 * descriptors alone would only prove that whatever was declared is well-formed; it would say nothing
 * about a tool someone added to a {@code @McpTool} bean and forgot to declare. Such a tool would be
 * advertised with no action, category or risk tags, and the gate would have nothing to authorize it
 * against — so the context refuses to start instead.
 *
 * <p>Both directions are checked, because both are drift:
 * <ul>
 *   <li>an <strong>advertised but undeclared</strong> tool would reach callers ungated;</li>
 *   <li>a <strong>declared but unadvertised</strong> tool means the registry describes something that
 *       does not exist — the roster filter (T5) would then reason about a phantom.</li>
 * </ul>
 *
 * <p>Bean <em>types</em> are read from the bean factory rather than by pulling every singleton, so the
 * scan does not force early initialization of unrelated beans.
 */
public final class ToolRegistryValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryValidator.class);

    private final ListableBeanFactory beanFactory;
    private final ToolRegistry registry;

    public ToolRegistryValidator(ListableBeanFactory beanFactory, ToolRegistry registry) {
        this.beanFactory = beanFactory;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> advertised = discoverAdvertisedToolNames();

        List<String> undeclared = advertised.stream()
                .filter(name -> registry.find(name).isEmpty())
                .sorted()
                .toList();
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "MCP tool(s) " + undeclared + " are advertised but declare no action/category/risk tags. "
                            + "An unclassifiable tool is not exposed — add a ToolDescriptor for each.");
        }

        List<String> unadvertised = registry.names().stream()
                .filter(name -> !advertised.contains(name))
                .sorted()
                .toList();
        if (!unadvertised.isEmpty()) {
            throw new IllegalStateException(
                    "Tool descriptor(s) " + unadvertised + " declare tools this server does not advertise. "
                            + "The registry must describe exactly the @McpTool surface.");
        }

        log.info("Tool registry validated: {} declared tool(s) match the advertised @McpTool surface {}",
                registry.all().size(), advertised);
    }

    /** Every tool name reachable over MCP, resolved the way the annotation model resolves it. */
    private Set<String> discoverAdvertisedToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type == null) {
                continue;
            }
            ReflectionUtils.doWithMethods(type, method -> {
                McpTool annotation = AnnotatedElementUtils.findMergedAnnotation(method, McpTool.class);
                if (annotation != null) {
                    names.add(toolName(annotation, method));
                }
            });
        }
        return names;
    }

    /** {@code @McpTool(name = …)} when given, otherwise the method name — the annotation model's rule. */
    private static String toolName(McpTool annotation, Method method) {
        String declared = annotation.name();
        return declared == null || declared.isBlank() ? method.getName() : declared;
    }
}
