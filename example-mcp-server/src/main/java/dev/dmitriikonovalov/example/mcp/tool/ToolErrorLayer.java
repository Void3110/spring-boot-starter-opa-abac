package dev.dmitriikonovalov.example.mcp.tool;

/**
 * The enforcement layer a tool-call denial came from — the vocabulary a caller sees on every structured
 * tool error.
 *
 * <p>Naming the layer is the point: an agent that is told <em>where</em> it was stopped can react (pick
 * another tool, ask its principal to escalate) instead of retrying blindly against a stack trace. The two
 * layers are enforced independently and never propagate authority between themselves:
 *
 * <ul>
 *   <li>{@link #TOOL_GATE} — this server's own gate: <em>may this (principal, actor) invoke this tool at
 *       all?</em> Decided before the tool body runs, so a denial makes no downstream request. Produced by
 *       the PEP (T4).</li>
 *   <li>{@link #TARGET_GATE} — the catalog service's unchanged per-type policy: <em>may this principal do
 *       this on this resource?</em> Observed here only as a downstream {@code 403}, translated by
 *       {@link CatalogApiErrorTranslator}.</li>
 * </ul>
 */
public enum ToolErrorLayer {

    /** This server's tool-gate (T4's PEP). Denied before the tool body ran. */
    TOOL_GATE("tool-gate"),

    /** The downstream catalog service's own resource gate, surfaced from its {@code 403}. */
    TARGET_GATE("target-gate");

    private final String label;

    ToolErrorLayer(String label) {
        this.label = label;
    }

    /** The stable, caller-facing label (e.g. {@code "tool-gate"}) carried in the structured tool error. */
    public String label() {
        return label;
    }
}
