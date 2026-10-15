package dev.dmitriikonovalov.opaabac.mcp.authz;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;

/**
 * Routes the tool-gate's decisions to the tool-gate document and leaves every other decision to the
 * application's own resolver — a <strong>decorator</strong>, never a replacement (ADR 0036 §13).
 *
 * <p>The starter builds one {@code OpaClient} from the context's {@link PolicyPathResolver}, and every
 * gate in the application shares it. Replacing that resolver would reroute the application's other
 * gates; refusing other types would break them. So: resource type {@value #TOOL_RESOURCE_TYPE} ⇒
 * {@code opa.abac.mcp.policy-path} (the <em>package</em> path — the batch call appends {@code /bulk});
 * any other type ⇒ exactly the delegate's answer. The resource type {@value #TOOL_RESOURCE_TYPE} is
 * therefore <strong>reserved</strong> for the tool-gate: an application resource of that type would be
 * decided by the agent policy.
 */
public class ToolPolicyPathResolver implements PolicyPathResolver {

    /** The reserved resource type every tool-gate context carries. */
    public static final String TOOL_RESOURCE_TYPE = "tool";

    private final PolicyPathResolver delegate;
    private final ToolAuthorizationProperties properties;

    /**
     * @param delegate   the application's resolver — answers every non-{@code tool} decision unchanged
     * @param properties the tool-gate settings ({@code opa.abac.mcp.policy-path})
     */
    public ToolPolicyPathResolver(PolicyPathResolver delegate, ToolAuthorizationProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    @Override
    public String resolve(AbacContext context) {
        String type = context == null || context.resource() == null ? null : context.resource().type();
        if (TOOL_RESOURCE_TYPE.equals(type)) {
            return properties.getPolicyPath();
        }
        return delegate.resolve(context);
    }
}
