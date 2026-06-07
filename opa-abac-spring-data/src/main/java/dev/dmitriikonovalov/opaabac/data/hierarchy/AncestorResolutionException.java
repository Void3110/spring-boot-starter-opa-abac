package dev.dmitriikonovalov.opaabac.data.hierarchy;

/**
 * Thrown when an {@link AncestorResolver} cannot produce a <em>complete, trustworthy</em> ancestor chain:
 * a cycle, a broken parent link, a depth-bound breach, a malformed/{@code NULL} lineage, or a SQL error.
 *
 * <p>This exception is the <strong>fail-closed signal</strong>. A resolver must <em>throw</em> rather than
 * return a partial or truncated chain — a truncated chain could silently under- or over-grant. Callers
 * treat it as "no inheritable lineage": they supply <b>no ancestors</b> to the decision, so the result can
 * only come from the resource's <em>direct</em> grant
 * ({@code final_allow = direct OR (walk_ok AND inherited)}). It never widens access and never strips a
 * direct grant.
 */
public class AncestorResolutionException extends RuntimeException {

    public AncestorResolutionException(String message) {
        super(message);
    }

    public AncestorResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
