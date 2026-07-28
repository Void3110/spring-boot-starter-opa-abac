package dev.dmitriikonovalov.example.mcp.tool;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The <strong>static, declared</strong> authorization attributes of one MCP tool: the action verb it
 * performs, the permission category that verb belongs to, and the risk tags that describe how sensitive
 * invoking it is.
 *
 * <p>These are the only source of a tool's attributes. Nothing is derived, defaulted, or inferred at call
 * time — the tool-gate policy (T3) reads exactly what was declared here, which is what makes a decision
 * auditable against the registration rather than against runtime behaviour.
 *
 * <p><strong>Fail-closed at registration.</strong> The compact constructor rejects a blank name, action,
 * or category and an empty risk-tag set. Because descriptors are created while the context is building, a
 * tool that cannot be classified fails startup and is therefore never exposed — the "unclassifiable tool"
 * edge of the design's fail-closed table. There is deliberately no permissive default.
 *
 * @param name     the MCP tool name as advertised (e.g. {@code list_catalogs})
 * @param action   the action verb, from the shipped permission vocabulary (e.g. {@code list}, {@code view})
 * @param category the permission category the verb expands from (e.g. {@code READ})
 * @param riskTags at least one risk tag; compared against an agent capability's max risk tag
 */
public record ToolDescriptor(String name, String action, String category, Set<String> riskTags) {

    public ToolDescriptor {
        name = requireText(name, "name", name);
        action = requireText(action, "action", name);
        category = requireText(category, "category", name);
        if (riskTags == null || riskTags.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tool '" + name + "' declares no risk tags; an unclassifiable tool is not exposed");
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String tag : riskTags) {
            copy.add(requireText(tag, "risk tag", name));
        }
        riskTags = Set.copyOf(copy);
    }

    private static String requireText(String value, String field, String toolName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Tool '" + toolName + "' declares a blank " + field
                            + "; an unclassifiable tool is not exposed");
        }
        return value.trim();
    }
}
