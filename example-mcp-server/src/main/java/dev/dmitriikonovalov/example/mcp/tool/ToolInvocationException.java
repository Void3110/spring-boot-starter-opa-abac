package dev.dmitriikonovalov.example.mcp.tool;

/**
 * A <strong>structured, advisory</strong> tool failure: a stable error {@code code}, the enforcement
 * {@link ToolErrorLayer} responsible (or {@code null} when the failure was not an authorization decision
 * at all — a transport outage, say), and a short message safe to hand to a model.
 *
 * <p>The contract this type exists to keep is that a caller never receives a raw stack trace, a bare 5xx,
 * or a silent empty result. Everything a tool can fail with is turned into one of these, so the agent on
 * the other end has something it can act on. Transport details (host, port, the underlying exception
 * type) stay in the log and never reach {@link #getMessage()}.
 */
public class ToolInvocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient ToolErrorLayer layer;

    /** A failure attributable to an enforcement layer (an authorization denial). */
    public ToolInvocationException(ToolErrorLayer layer, String code, String message) {
        super(message);
        this.layer = layer;
        this.code = code;
    }

    /** A failure with no enforcement layer behind it — a downstream outage, a malformed response. */
    public ToolInvocationException(String code, String message) {
        this(null, code, message);
    }

    /** The stable error code, e.g. {@code catalog-forbidden} — safe to branch on. */
    public String code() {
        return code;
    }

    /** The denying layer, or {@code null} when the failure was not an authorization decision. */
    public ToolErrorLayer layer() {
        return layer;
    }

    /** The layer's caller-facing label, or {@code null} when there is no layer. */
    public String layerLabel() {
        return layer == null ? null : layer.label();
    }
}
