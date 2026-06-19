package dev.dmitriikonovalov.opaabac.security.resilience;

/**
 * Thrown by a {@link CallGuard} when an <strong>open circuit breaker</strong> short-circuits a call — the
 * body is not invoked at all. Backend-agnostic by design (Slice B3, ADR 0017 §7): callers catch this, not
 * Resilience4j's {@code CallNotPermittedException}, so swapping the resilience backend never changes a
 * caller's catch clause.
 *
 * <p>A caller maps this to its own fail-closed value exactly as it maps an exhausted retry — the OPA
 * decorator to {@code false} / {@code PartialResult.error()} / all-false, the resolve wrapper to
 * {@code RoleResolutionException}, the tag wrapper to {@code TagDefinitionFetchException}. An open breaker
 * is therefore strictly <em>more</em> fail-closed, never less (ADR 0017 §5): it changes <em>when</em> and
 * <em>how fast</em> the call fails closed, never <em>whether</em> the answer is fail-closed.
 */
public class CallNotPermittedException extends RuntimeException {

    public CallNotPermittedException(String message, Throwable cause) {
        super(message, cause);
    }
}
