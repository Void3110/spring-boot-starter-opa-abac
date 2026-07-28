package dev.dmitriikonovalov.example.mcp.authz;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;

/**
 * Routes the {@code tool} resource type to the tool-gate document, and refuses anything else.
 *
 * <p>This server asks exactly one policy question, so a context for any other resource type reaching
 * here is a bug rather than a routing case. Throwing is the fail-closed answer: the shipped client
 * catches it and denies, whereas quietly defaulting to some other document would evaluate a tool call
 * against a policy written for a different question.
 */
public class ToolPolicyPathResolver implements PolicyPathResolver {

    static final String TOOL_RESOURCE_TYPE = "tool";

    private final ToolAuthorizationProperties properties;

    public ToolPolicyPathResolver(ToolAuthorizationProperties properties) {
        this.properties = properties;
    }

    @Override
    public String resolve(AbacContext context) {
        String type = context == null || context.resource() == null ? null : context.resource().type();
        if (!TOOL_RESOURCE_TYPE.equals(type)) {
            throw new IllegalArgumentException(
                    "The MCP server only decides 'tool' resources; got '" + type + "'");
        }
        return properties.getPolicyPath();
    }
}
