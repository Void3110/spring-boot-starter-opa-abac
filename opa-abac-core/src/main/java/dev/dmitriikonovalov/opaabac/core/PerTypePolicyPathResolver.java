package dev.dmitriikonovalov.opaabac.core;

/**
 * Default {@link PolicyPathResolver}: one OPA document per resource type.
 *
 * <p>Returns {@code <policyPrefix>/<resourceType>}, dropping any blank segment — so a blank prefix
 * yields just {@code <resourceType>}. This is the production-shaped default: each resource type's
 * rules, tests, and ownership live in their own rego document.
 */
public final class PerTypePolicyPathResolver implements PolicyPathResolver {

    private final String policyPrefix;

    /**
     * @param policyPrefix a path prefix under OPA's data document (may be blank/null for no prefix)
     */
    public PerTypePolicyPathResolver(String policyPrefix) {
        this.policyPrefix = trimSlashes(policyPrefix);
    }

    @Override
    public String resolve(AbacContext context) {
        String type = context.resource() == null ? null : context.resource().type();
        String trimmedType = trimSlashes(type);
        if (policyPrefix.isEmpty()) {
            return trimmedType;
        }
        if (trimmedType.isEmpty()) {
            return policyPrefix;
        }
        return policyPrefix + "/" + trimmedType;
    }

    private static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        int start = 0;
        int end = trimmed.length();
        while (start < end && trimmed.charAt(start) == '/') {
            start++;
        }
        while (end > start && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(start, end);
    }
}
