package dev.dmitriikonovalov.opaabac.core;

/**
 * The authorization decision <strong>could not be made</strong> — distinct from a decision that said no
 * (ADR 0037). XACML calls this state <em>Indeterminate</em>.
 *
 * <p>A deny is a final answer: a caller renders it as a 403 and retrying it changes nothing. This is the
 * other state — the policy engine was unreachable, timed out, answered something that is not a decision,
 * or an input the decision needs (the caller's role) could not be fetched. A caller renders it as "not
 * now" (a 503), and a durable workflow retries it rather than recording a final refusal.
 *
 * <p><strong>Still fail-closed.</strong> A thrown exception is never an allow; nothing that catches this
 * may turn it into one. What changes against a returned deny is only that the two states can be told
 * apart — and that a caller who forgets to handle it fails loudly instead of silently reporting an outage
 * as a refusal.
 *
 * <p>Two members ship with the library: {@link PolicyEngineException} (the OPA call failed) and
 * {@link RoleResolutionException} (the role source was unavailable, ADR 0014). The type is abstract with
 * protected constructors <strong>so an SPI implementation can opt its own outage in</strong> — a resource
 * resolver, an ancestor-chain supplier or a custom {@link OpaClient} throws a subtype, and every library
 * layer propagates it instead of degrading it. A failure that is <em>not</em> a member of this family keeps
 * whatever fail-closed outcome the layer gave it before.
 *
 * <p>Unchecked, like the rest of the library's fail-closed signals: an outage is an infrastructure failure,
 * not a business condition the immediate caller can repair. The wrapped cause is for logs only and is never
 * surfaced to a client.
 */
public abstract class DecisionIndeterminateException extends RuntimeException {

    protected DecisionIndeterminateException(String message) {
        super(message);
    }

    protected DecisionIndeterminateException(String message, Throwable cause) {
        super(message, cause);
    }
}
