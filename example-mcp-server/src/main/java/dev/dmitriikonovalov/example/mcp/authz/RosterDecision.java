package dev.dmitriikonovalov.example.mcp.authz;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the roster pre-flight concluded for one {@code tools/list} — either a set of tool names the caller
 * may see, or "serve the delegate's list unchanged".
 *
 * <h2>Why "unfiltered" is a first-class outcome and not an empty set</h2>
 * The two are opposites and conflating them is the bug this type exists to prevent. An <strong>empty
 * allow-set</strong> is an <em>answer</em>: the policy said no to every tool, so the caller sees nothing.
 * {@link #unfiltered()} is the <em>absence</em> of an answer: something outside the batch failed — an
 * unreadable identity, a capability or ceiling lookup that threw — and the hint degrades to showing
 * everything, because it carries no authority and the call-time gate still denies each tool
 * individually. A boolean-free {@code Set} return would have to encode "I don't know" as either
 * {@code null} or the full name set, and both read as data rather than as a decision.
 *
 * @param allowedToolNames the tool names to keep; {@code null} means "no decision — serve unfiltered"
 */
public record RosterDecision(Set<String> allowedToolNames) {

    private static final RosterDecision UNFILTERED = new RosterDecision(null);

    public RosterDecision {
        allowedToolNames = allowedToolNames == null ? null : Set.copyOf(allowedToolNames);
    }

    /** The caller may see exactly these tools. An empty set is a real answer: an empty roster. */
    public static RosterDecision allowing(Set<String> toolNames) {
        return new RosterDecision(new LinkedHashSet<>(toolNames));
    }

    /** No decision could be reached off the batch's edges — serve the delegate's list as-is. */
    public static RosterDecision unfiltered() {
        return UNFILTERED;
    }

    /** True when the delegate's list must be served untouched. */
    public boolean isUnfiltered() {
        return allowedToolNames == null;
    }

    /** True when {@code toolName} survives this decision. Always true for an unfiltered decision. */
    public boolean permits(String toolName) {
        return isUnfiltered() || allowedToolNames.contains(toolName);
    }
}
