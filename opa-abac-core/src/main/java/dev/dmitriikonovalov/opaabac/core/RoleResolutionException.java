package dev.dmitriikonovalov.opaabac.core;

/**
 * Thrown by a {@link RoleDefinitionSupplier} when the role <em>source</em> was unavailable — a timeout,
 * a connection failure, a server error, or a malformed response — so the caller's role is
 * <strong>unknown</strong>, not authoritatively absent.
 *
 * <p>This exception is the <strong>fail-closed signal</strong> for the role-source seam. It is distinct
 * from an authoritative <em>no-role</em> ({@link java.util.Optional#empty()}), which is a designed signal
 * a policy may fall back on (e.g. a subject's realm roles). An <em>outage</em> must never be treated as
 * no-role: doing so would let a policy fall back to a grant <em>wider</em> than the subject's resolved
 * role. So every consumer of {@link RoleDefinitionSupplier#lookup} that catches this exception MUST
 * <strong>fail closed</strong> — deny, return no result, never widen — and never fall back.
 *
 * <p>An in-process, deterministic supplier (no remote source to be unavailable) never throws this; the
 * value path ({@code Optional.of} / {@code Optional.empty}) covers it entirely. Only a supplier backed by
 * a remote/queried source classifies its failures into this exception.
 *
 * <p>Unchecked by design: an outage is an infrastructure failure, not a recoverable business condition,
 * and a checked exception on the single abstract method would force every {@code @FunctionalInterface}
 * lambda to declare or wrap it. Family-consistent with {@code AncestorResolutionException} (the
 * chain-collapse fail-closed signal), which is a <strong>separate failure axis</strong> and must not be
 * conflated with this one. The wrapped {@code cause} is for logs only and is never surfaced to a client.
 *
 * @see RoleDefinitionSupplier
 */
public class RoleResolutionException extends RuntimeException {

    public RoleResolutionException(String message) {
        super(message);
    }

    public RoleResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
