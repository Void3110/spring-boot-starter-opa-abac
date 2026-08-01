package dev.dmitriikonovalov.example.mcp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * U16–U17: the memo is <strong>turn-scoped, never session-lifetime</strong>.
 *
 * <p>One turn must see one capability answer, so the roster pre-flight and the call it precedes cannot
 * disagree. But the memo must die at the turn boundary, or revoking an agent mid-session would not take
 * effect until the client reconnected — and "the list said I could" would quietly become an
 * authorization.
 */
class TurnScopedCapabilityCacheTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<AgentCapabilityProfile> answer =
            new AtomicReference<>(profile("low"));

    private final AgentCapabilitySupplier delegate = actorId -> {
        calls.incrementAndGet();
        return answer.get();
    };

    private final TurnScopedCapabilityCache cache = new TurnScopedCapabilityCache(delegate);

    private static AgentCapabilityProfile profile(String maxRisk) {
        return new AgentCapabilityProfile(
                Set.of("READ"), Set.of("list_catalogs"), Set.of("list"), maxRisk);
    }

    @AfterEach
    void endTurn() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Begin a fresh turn — one MCP request. */
    private static void beginTurn() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test // U16 — two lookups in one turn hit the source once
    void consultsTheSourceOncePerActorPerTurn() {
        beginTurn();

        assertThat(cache.lookup("agent-a")).isEqualTo(profile("low"));
        assertThat(cache.lookup("agent-a")).isEqualTo(profile("low"));
        assertThat(cache.lookup("agent-a")).isEqualTo(profile("low"));

        assertThat(calls).hasValue(1);
    }

    @Test // U16 — the next turn asks again, so a revocation between turns takes effect
    void resolvesAfreshOnTheNextTurn() {
        beginTurn();
        assertThat(cache.lookup("agent-a").maxRiskTag()).isEqualTo("low");
        assertThat(calls).hasValue(1);

        // The agent's capability is revoked between turns.
        answer.set(AgentCapabilityProfile.empty());

        RequestContextHolder.resetRequestAttributes();
        beginTurn();

        assertThat(cache.lookup("agent-a")).isEqualTo(AgentCapabilityProfile.empty());
        assertThat(calls).hasValue(2);
    }

    @Test // U17 — no cross-actor bleed inside one turn
    void keepsActorsSeparateWithinATurn() {
        AtomicReference<String> lastActor = new AtomicReference<>();
        AgentCapabilitySupplier perActor = actorId -> {
            calls.incrementAndGet();
            lastActor.set(actorId);
            return new AgentCapabilityProfile(
                    Set.of("READ"), Set.of(actorId + "-tool"), Set.of("list"), "low");
        };
        TurnScopedCapabilityCache perActorCache = new TurnScopedCapabilityCache(perActor);
        beginTurn();

        assertThat(perActorCache.lookup("agent-a").allowedTools()).containsExactly("agent-a-tool");
        assertThat(perActorCache.lookup("agent-b").allowedTools()).containsExactly("agent-b-tool");
        // ...and each is still memoized independently.
        assertThat(perActorCache.lookup("agent-a").allowedTools()).containsExactly("agent-a-tool");

        assertThat(calls).hasValue(2);
        assertThat(lastActor).hasValue("agent-b");
    }

    @Test // U17 — a profile resolved in one turn is never served to the next
    void neverServesOneTurnsAnswerToAnother() {
        beginTurn();
        cache.lookup("agent-a");
        RequestContextHolder.resetRequestAttributes();

        answer.set(profile("high"));
        beginTurn();

        assertThat(cache.lookup("agent-a").maxRiskTag()).isEqualTo("high");
    }

    @Test // the outage is memoized too — a turn degrades consistently, never halfway
    void replaysAnOutageForTheRestOfTheTurn() {
        AgentCapabilitySupplier failing = actorId -> {
            calls.incrementAndGet();
            throw new AgentCapabilityUnavailableException("down", new IllegalStateException("boom"));
        };
        TurnScopedCapabilityCache failingCache = new TurnScopedCapabilityCache(failing);
        beginTurn();

        assertThatThrownBy(() -> failingCache.lookup("agent-a"))
                .isInstanceOf(AgentCapabilityUnavailableException.class);
        assertThatThrownBy(() -> failingCache.lookup("agent-a"))
                .isInstanceOf(AgentCapabilityUnavailableException.class);

        assertThat(calls).hasValue(1);
    }

    @Test // the outage does not outlive its turn either
    void retriesTheSourceOnTheNextTurnAfterAnOutage() {
        AtomicInteger attempts = new AtomicInteger();
        AgentCapabilitySupplier flaky = actorId -> {
            if (attempts.incrementAndGet() == 1) {
                throw new AgentCapabilityUnavailableException("down", null);
            }
            return profile("low");
        };
        TurnScopedCapabilityCache flakyCache = new TurnScopedCapabilityCache(flaky);

        beginTurn();
        assertThatThrownBy(() -> flakyCache.lookup("agent-a"))
                .isInstanceOf(AgentCapabilityUnavailableException.class);
        RequestContextHolder.resetRequestAttributes();

        beginTurn();
        assertThat(flakyCache.lookup("agent-a").maxRiskTag()).isEqualTo("low");
    }

    @Test // outside a request there is no turn to scope to — pass through, memoize nothing
    void passesThroughWhenThereIsNoTurnInScope() {
        RequestContextHolder.resetRequestAttributes();

        cache.lookup("agent-a");
        cache.lookup("agent-a");

        assertThat(calls).hasValue(2);
    }
}
