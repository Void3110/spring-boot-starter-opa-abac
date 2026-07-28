package dev.dmitriikonovalov.example.mcp.authz;

import dev.dmitriikonovalov.example.mcp.tool.ToolErrorLayer;

/**
 * The tool-gate's answer: allowed, or denied with a stable code and the layer responsible.
 *
 * <p>A denial names its layer so a model can react rather than retry blindly — {@code tool-gate} means
 * "you may not use this tool", which calls for choosing another or asking the human to escalate;
 * {@code target-gate} means "you may use this tool, but not on that row", which calls for a different
 * target. Those are different problems with different remedies, and collapsing them into one opaque
 * failure is what makes an agent flail.
 *
 * <p>The {@code code} distinguishes <em>why</em> for operators — a policy denial, a capability outage, an
 * unreadable identity — while {@code message} stays deliberately uniform across them, so the difference
 * is diagnosable in a log without becoming an oracle for whoever is probing.
 */
public record ToolAuthorizationDecision(
        boolean allowed, ToolErrorLayer layer, String code, String message) {

    /** The advisory text every denial carries, regardless of its internal cause. */
    static final String DENIED_MESSAGE =
            "This tool call was not permitted for the calling agent and principal.";

    /** Named {@code permitted} rather than {@code allowed} — the latter is the record's own accessor. */
    public static ToolAuthorizationDecision permitted() {
        return new ToolAuthorizationDecision(true, null, null, null);
    }

    /** A denial at the tool-gate, with an internal code that never varies the caller-facing text. */
    public static ToolAuthorizationDecision denied(String code) {
        return new ToolAuthorizationDecision(false, ToolErrorLayer.TOOL_GATE, code, DENIED_MESSAGE);
    }
}
