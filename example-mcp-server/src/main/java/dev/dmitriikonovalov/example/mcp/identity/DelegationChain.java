package dev.dmitriikonovalov.example.mcp.identity;

import java.util.List;

/**
 * Who is acting, and on whose behalf — the dual identity behind one bearer token.
 *
 * <p>A classic REST call has exactly one identity: the token's {@code sub}. An agent tool call has two,
 * and collapsing them is the gap this slice exists to close: without the distinction a policy can only
 * bound the <em>human</em>, so the agent inherits their whole ceiling.
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li>{@code principal} — the human, the token {@code sub}, resolved exactly as the rest of the repo
 *       resolves it (the starter's subject extractor). Never null or blank.</li>
 *   <li>{@code actor} — the agent making this call, or {@code null} for an ordinary human call.</li>
 *   <li>{@code chain} — the ordered actor chain, <strong>nearest actor first</strong>, excluding the
 *       principal. Empty exactly when this is a human call; otherwise {@code chain.get(0) == actor}.</li>
 * </ul>
 *
 * <p>The principal is deliberately <em>not</em> an element of {@code chain}: the chain has one meaning —
 * agents — and mixing the human into it would make a depth limit ambiguous and a cycle check subtle.
 *
 * <p>This type <strong>describes</strong>; it never grants. Nothing here is an authorization decision:
 * the tool-gate policy reads it as input and decides separately.
 */
public record DelegationChain(String principal, String actor, List<String> chain) {

    public DelegationChain {
        chain = chain == null ? List.of() : List.copyOf(chain);
    }

    /** An ordinary human call — a principal and no actor. */
    public static DelegationChain ofPrincipal(String principal) {
        return new DelegationChain(principal, null, List.of());
    }

    /** True when an agent is acting for the principal. */
    public boolean isAgentCall() {
        return actor != null;
    }

    /** How many agents deep the delegation goes; 0 for a human call. */
    public int depth() {
        return chain.size();
    }
}
