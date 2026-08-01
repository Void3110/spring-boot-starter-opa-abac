package dev.dmitriikonovalov.example.mcp.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * A <strong>turn-scoped</strong> memoizing decorator over an {@link AgentCapabilitySupplier}: within
 * one turn the delegate is consulted <strong>once</strong> per actor and its first outcome is replayed.
 *
 * <h2>A turn is one MCP request — never a session</h2>
 * This is the whole point, and it is where this differs from the request-scoped resolve memo of
 * ADR 0023 that it otherwise copies. A tool call and the {@code tools/list} pre-flight that precedes
 * it must see <em>one</em> capability answer, or the roster and the gate could disagree inside a single
 * turn. But the memo must die at the turn boundary: if it lived for the session, revoking an agent's
 * capability mid-session would not take effect until the client reconnected, and "the list said I could"
 * would quietly become an authorization. Turn-scoped means a revocation lands on the very next turn.
 *
 * <p>Scoping is delegated to the servlet request attributes, which die with the request — no TTL, no
 * cross-turn store, no invalidation protocol to get wrong.
 *
 * <h2>All three outcomes are memoized</h2>
 * A resolved profile, an authoritative-empty profile, and the
 * {@link AgentCapabilityUnavailableException} <em>outage</em> — the last stored as a marker and
 * <strong>re-thrown</strong> for the rest of the turn. Memoizing only the successes would let one turn
 * see an outage for the roster and a resolved profile for the call, i.e. a mixed snapshot; a turn that
 * degrades consistently is preferable to one that degrades halfway.
 *
 * <h2>No request context is a clean pass-through</h2>
 * Outside a request (a scheduler, a plain unit test) the delegate is reached on every call and nothing
 * is memoized. The decorator never throws from its own bookkeeping: a memo bug degrades to an extra
 * lookup, never to a wrong decision.
 */
public class TurnScopedCapabilityCache implements AgentCapabilitySupplier {

    private static final String ATTRIBUTE_PREFIX =
            TurnScopedCapabilityCache.class.getName() + ".capability.";

    private static final Logger log = LoggerFactory.getLogger(TurnScopedCapabilityCache.class);

    private final AgentCapabilitySupplier delegate;

    public TurnScopedCapabilityCache(AgentCapabilitySupplier delegate) {
        this.delegate = delegate;
    }

    @Override
    public AgentCapabilityProfile lookup(String actorId) {
        RequestAttributes attributes = currentTurn();
        if (attributes == null) {
            return delegate.lookup(actorId);
        }

        String key = ATTRIBUTE_PREFIX + actorId;
        Object memoized = read(attributes, key);
        if (memoized instanceof AgentCapabilityProfile profile) {
            return profile;
        }
        if (memoized instanceof OutageMarker(Throwable cause)) {
            // Replay the outage for the whole turn: a caller that already denied on it must not see a
            // resolved profile a moment later in the same turn.
            throw new AgentCapabilityUnavailableException(
                    "The agent capability source could not be read.", cause);
        }

        try {
            AgentCapabilityProfile resolved = delegate.lookup(actorId);
            write(attributes, key, resolved);
            return resolved;
        } catch (AgentCapabilityUnavailableException e) {
            write(attributes, key, new OutageMarker(e.getCause()));
            throw e;
        }
    }

    /** The current turn's attribute store, or null when there is no request in scope. */
    private static RequestAttributes currentTurn() {
        try {
            return RequestContextHolder.getRequestAttributes();
        } catch (RuntimeException e) {
            log.debug("No turn scope available; passing through to the capability source", e);
            return null;
        }
    }

    private static Object read(RequestAttributes attributes, String key) {
        try {
            return attributes.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
        } catch (RuntimeException e) {
            log.debug("Turn-scoped capability memo unreadable; passing through", e);
            return null;
        }
    }

    private static void write(RequestAttributes attributes, String key, Object value) {
        try {
            attributes.setAttribute(key, value, RequestAttributes.SCOPE_REQUEST);
        } catch (RuntimeException e) {
            // Bookkeeping must never change a decision — worst case we look the answer up again.
            log.debug("Turn-scoped capability memo unwritable; not memoizing", e);
        }
    }

    /** Marks "this actor's capability was unknown in this turn", so the outage replays. */
    private record OutageMarker(Throwable cause) {}
}
