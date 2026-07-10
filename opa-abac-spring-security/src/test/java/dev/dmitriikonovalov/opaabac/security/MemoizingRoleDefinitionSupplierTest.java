package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.ResolveTarget;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Unit tests for {@link MemoizingRoleDefinitionSupplier} (ADR 0023; QA cases U1–U4, U6): all three
 * tri-state outcomes memoized per key within one request, keys never collide, nothing survives a
 * request, no-request is a byte-identical pass-through, and "one request, one answer per target"
 * survives a supplier that flips answers mid-request (the ADR's disprover).
 */
class MemoizingRoleDefinitionSupplierTest {

    private static final RoleDefinition ROLE_A = new RoleDefinition("role-a", Map.of(), Map.of());
    private static final RoleDefinition ROLE_B = new RoleDefinition("role-b", Map.of(), Map.of());

    private final CountingSupplier delegate = new CountingSupplier();
    private final MemoizingRoleDefinitionSupplier memo = new MemoizingRoleDefinitionSupplier(delegate);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test // U1 — resolved: one delegate call per key, the value replayed
    void memoizesResolvedOutcome() {
        bindRequest();
        delegate.answer("u", "catalog", "c-1", () -> Optional.of(ROLE_A));

        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);

        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(1);
    }

    @Test // U1 — authoritative no-role: empty is an outcome, memoized like a value
    void memoizesAuthoritativeEmpty() {
        bindRequest();
        delegate.answer("u", "catalog", "c-1", Optional::empty);

        assertThat(memo.lookup("u", "catalog", "c-1")).isEmpty();
        assertThat(memo.lookup("u", "catalog", "c-1")).isEmpty();

        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(1);
    }

    @Test // U1 — outage: the throw is memoized and RE-THROWN on replay, delegate untouched again
    void memoizesOutageAndReplaysTheThrow() {
        bindRequest();
        RoleResolutionException outage = new RoleResolutionException("role source down");
        delegate.answer("u", "catalog", "c-1", () -> {
            throw outage;
        });

        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1")).isSameAs(outage);
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1")).isSameAs(outage);
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1")).isSameAs(outage);

        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(1);
    }

    @Test // U1 — the memo replays, never reinterprets: an outage never becomes empty on a later call
    void outageNeverDecaysIntoNoRole() {
        bindRequest();
        Deque<Supplier<Optional<RoleDefinition>>> script = new ArrayDeque<>();
        script.add(() -> {
            throw new RoleResolutionException("blip");
        });
        script.add(() -> Optional.of(ROLE_A)); // the source recovers mid-request…
        delegate.answer("u", "catalog", "c-1", () -> script.pop().get());

        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
        // …but this request already has its answer for the key: the outage, replayed.
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1"))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(1);
    }

    @Test // U2 — distinct (userId, resourceType, resourceId) keys never collide
    void keysNeverCollide() {
        bindRequest();
        delegate.answer("u1", "catalog", "c-1", () -> Optional.of(ROLE_A));
        delegate.answer("u2", "catalog", "c-1", () -> Optional.of(ROLE_B));
        delegate.answer("u1", "category", "c-1", () -> Optional.empty());

        assertThat(memo.lookup("u1", "catalog", "c-1")).contains(ROLE_A);
        assertThat(memo.lookup("u2", "catalog", "c-1")).contains(ROLE_B);
        assertThat(memo.lookup("u1", "category", "c-1")).isEmpty();
        assertThat(memo.lookup("u1", "catalog", "c-1")).contains(ROLE_A);

        assertThat(delegate.calls("u1", "catalog", "c-1")).isEqualTo(1);
        assertThat(delegate.calls("u2", "catalog", "c-1")).isEqualTo(1);
        assertThat(delegate.calls("u1", "category", "c-1")).isEqualTo(1);
    }

    @Test // U2 — a null resourceId (type-level check) is its own key, distinct from every instance
    void nullResourceIdIsItsOwnKey() {
        bindRequest();
        delegate.answer("u", "catalog", null, () -> Optional.of(ROLE_A));
        delegate.answer("u", "catalog", "null", () -> Optional.of(ROLE_B)); // a literal "null" id
        delegate.answer("u", "catalog", "c-1", Optional::empty);

        assertThat(memo.lookup("u", "catalog", null)).contains(ROLE_A);
        assertThat(memo.lookup("u", "catalog", "null")).contains(ROLE_B);
        assertThat(memo.lookup("u", "catalog", "c-1")).isEmpty();
        assertThat(memo.lookup("u", "catalog", null)).contains(ROLE_A);

        assertThat(delegate.calls("u", "catalog", null)).isEqualTo(1);
        assertThat(delegate.calls("u", "catalog", "null")).isEqualTo(1);
        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(1);
    }

    @Test // U3 — nothing survives a request: a fresh request re-hits the delegate
    void nothingSurvivesTheRequest() {
        bindRequest();
        delegate.answer("u", "catalog", "c-1", () -> Optional.of(ROLE_A));
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);
        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(1);

        bindRequest(); // the next request: fresh attributes
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);
        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(2);
    }

    @Test // U4 — no request bound: every call reaches the delegate, zero memoization
    void noRequestIsPurePassThrough() {
        RequestContextHolder.resetRequestAttributes();
        delegate.answer("u", "catalog", "c-1", () -> Optional.of(ROLE_A));

        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);

        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(2);
    }

    @Test // U4 — outcomes pass through byte-identically outside a request, incl. the throw
    void passThroughPreservesOutcomesVerbatim() {
        RequestContextHolder.resetRequestAttributes();
        RoleResolutionException outage = new RoleResolutionException("down");
        delegate.answer("u", "catalog", "c-1", () -> {
            throw outage;
        });

        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1")).isSameAs(outage);
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1")).isSameAs(outage);
        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(2); // not memoized either
    }

    @Test // U4 — a non-contract exception is neither swallowed nor memoized (a bug is not an outcome)
    void nonContractExceptionPropagatesUnmemoized() {
        bindRequest();
        delegate.answer("u", "catalog", "c-1", () -> {
            throw new IllegalStateException("a delegate bug");
        });

        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(2);
    }

    @Test // U4 — bookkeeping never throws: a request whose attributes refuse access degrades to pass-through
    void bookkeepingFailureDegradesToPassThrough() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        attributes.requestCompleted(); // SCOPE_REQUEST access now throws IllegalStateException
        delegate.answer("u", "catalog", "c-1", () -> Optional.of(ROLE_A));

        assertThatCode(() -> {
            assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);
            assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);
        }).doesNotThrowAnyException();
        assertThat(delegate.calls("u", "catalog", "c-1")).isEqualTo(2); // reuse lost, decision kept
    }

    @Test // U6 — THE ADR 0023 DISPROVER: a delegate that flips A→B mid-request; every consumer sees A
    void supplierFlipYieldsOneAnswerPerRequest() {
        bindRequest();
        Deque<Optional<RoleDefinition>> script = new ArrayDeque<>();
        script.add(Optional.of(ROLE_A));
        script.add(Optional.of(ROLE_B));
        delegate.answer("u", "catalog", "c-1", script::pop);

        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A); // the gate
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A); // the list authorizer
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A); // an enriched row

        // The flip lands at the NEXT request boundary — revocation latency is request-bounded.
        bindRequest();
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_B);
    }

    // --- U9: memo × batch integration -------------------------------------------

    @Test // U9 — memoized keys are EXCLUDED from the delegated set; misses go down as ONE lookupAll
    void batchExcludesHitsAndDelegatesMissesOnce() {
        bindRequest();
        ResolveTarget hit = new ResolveTarget("catalog", "c-hit");
        ResolveTarget missA = new ResolveTarget("catalog", "c-a");
        ResolveTarget missB = new ResolveTarget("catalog", "c-b");
        delegate.answer("u", "catalog", "c-hit", () -> Optional.of(ROLE_A));
        delegate.answer("u", "catalog", "c-a", () -> Optional.of(ROLE_B));
        delegate.answer("u", "catalog", "c-b", Optional::empty);
        memo.lookup("u", "catalog", "c-hit"); // memoize the hit

        Map<ResolveTarget, Optional<RoleDefinition>> result =
                memo.lookupAll("u", Set.of(hit, missA, missB));

        assertThat(result).containsOnlyKeys(hit, missA, missB); // strictly complete merge
        assertThat(result.get(hit)).contains(ROLE_A);
        assertThat(result.get(missA)).contains(ROLE_B);
        assertThat(result.get(missB)).isEmpty();
        assertThat(delegate.batchCalls).singleElement().satisfies(batch -> {
            assertThat(batch).containsExactlyInAnyOrder(missA, missB); // the hit never delegated
        });
        assertThat(delegate.calls("u", "catalog", "c-hit")).isEqualTo(1); // only the initial lookup
    }

    @Test // U9 — batch results are memoized: later single lookups replay without the delegate
    void batchResultsFeedTheSingleLookupMemo() {
        bindRequest();
        ResolveTarget target = new ResolveTarget("catalog", "c-1");
        delegate.answer("u", "catalog", "c-1", () -> Optional.of(ROLE_A));

        memo.lookupAll("u", Set.of(target));
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);
        assertThat(memo.lookup("u", "catalog", "c-1")).contains(ROLE_A);

        assertThat(delegate.calls("u", "catalog", "c-1")).isZero(); // no single-lookup delegation
        assertThat(delegate.batchCalls).hasSize(1); // the one batch resolved it
    }

    @Test // U9 — a whole-batch outage memoizes the marker for EVERY missed target
    void batchOutageMarksEveryMiss() {
        bindRequest();
        ResolveTarget a = new ResolveTarget("catalog", "c-a");
        ResolveTarget b = new ResolveTarget("catalog", "c-b");
        RoleResolutionException outage = new RoleResolutionException("role source down");
        delegate.batchFailure = outage;

        assertThatThrownBy(() -> memo.lookupAll("u", Set.of(a, b))).isSameAs(outage);

        // A later SINGLE lookup of either target replays the throw with the delegate untouched.
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-a")).isSameAs(outage);
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-b")).isSameAs(outage);
        assertThat(delegate.calls("u", "catalog", "c-a")).isZero();
        assertThat(delegate.calls("u", "catalog", "c-b")).isZero();
        assertThat(delegate.batchCalls).hasSize(1);
    }

    @Test // U9 — a memoized outage HIT inside a batch re-throws (a batch never yields partial roles)
    void memoizedOutageHitFailsTheWholeBatch() {
        bindRequest();
        RoleResolutionException outage = new RoleResolutionException("down");
        delegate.answer("u", "catalog", "c-out", () -> {
            throw outage;
        });
        delegate.answer("u", "catalog", "c-ok", () -> Optional.of(ROLE_A));
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-out")).isSameAs(outage);

        assertThatThrownBy(() -> memo.lookupAll("u", Set.of(
                new ResolveTarget("catalog", "c-out"), new ResolveTarget("catalog", "c-ok"))))
                .isSameAs(outage);
        assertThat(delegate.batchCalls).isEmpty(); // failed before any delegation
    }

    @Test // U9 — a delegate violating strict completeness is a whole-batch outage, memoized
    void incompleteDelegateBatchIsAnOutage() {
        bindRequest();
        ResolveTarget a = new ResolveTarget("catalog", "c-a");
        ResolveTarget b = new ResolveTarget("catalog", "c-b");
        delegate.batchOverride = misses -> Map.of(a, Optional.of(ROLE_A)); // short map

        assertThatThrownBy(() -> memo.lookupAll("u", Set.of(a, b)))
                .isInstanceOf(RoleResolutionException.class)
                .hasMessageContaining("contract violation");
        // The violation is memoized as the outage for BOTH misses.
        assertThatThrownBy(() -> memo.lookup("u", "catalog", "c-a"))
                .isInstanceOf(RoleResolutionException.class);
        assertThat(delegate.calls("u", "catalog", "c-a")).isZero();
    }

    @Test // U9 — empty target set: empty map, no delegation, no memo touch
    void emptyBatchShortCircuits() {
        bindRequest();

        assertThat(memo.lookupAll("u", Set.of())).isEmpty();
        assertThat(delegate.batchCalls).isEmpty();
    }

    @Test // U9 — no request bound: the batch passes through verbatim
    void batchPassesThroughWithoutRequest() {
        RequestContextHolder.resetRequestAttributes();
        ResolveTarget a = new ResolveTarget("catalog", "c-a");
        delegate.answer("u", "catalog", "c-a", () -> Optional.of(ROLE_A));

        memo.lookupAll("u", Set.of(a));
        memo.lookupAll("u", Set.of(a));

        assertThat(delegate.batchCalls).hasSize(2); // nothing memoized
    }

    /** A scriptable counting delegate: one programmable answer per key, calls counted per key. */
    private static final class CountingSupplier implements RoleDefinitionSupplier {

        private final Map<String, Supplier<Optional<RoleDefinition>>> answers = new HashMap<>();
        private final Map<String, Integer> counts = new HashMap<>();
        final List<Set<ResolveTarget>> batchCalls = new ArrayList<>();
        RoleResolutionException batchFailure;
        java.util.function.Function<Set<ResolveTarget>, Map<ResolveTarget, Optional<RoleDefinition>>>
                batchOverride;

        void answer(String userId, String type, String id, Supplier<Optional<RoleDefinition>> outcome) {
            answers.put(key(userId, type, id), outcome);
        }

        int calls(String userId, String type, String id) {
            return counts.getOrDefault(key(userId, type, id), 0);
        }

        @Override
        public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
            String key = key(userId, resourceType, resourceId);
            counts.merge(key, 1, Integer::sum);
            Supplier<Optional<RoleDefinition>> outcome = answers.get(key);
            if (outcome == null) {
                throw new AssertionError("unscripted lookup: " + key);
            }
            return outcome.get();
        }

        @Override
        public Map<ResolveTarget, Optional<RoleDefinition>> lookupAll(
                String userId, Set<ResolveTarget> targets) {
            batchCalls.add(targets);
            if (batchFailure != null) {
                throw batchFailure;
            }
            if (batchOverride != null) {
                return batchOverride.apply(targets);
            }
            Map<ResolveTarget, Optional<RoleDefinition>> out = new HashMap<>();
            for (ResolveTarget t : targets) {
                Supplier<Optional<RoleDefinition>> outcome =
                        answers.get(key(userId, t.resourceType(), t.resourceId()));
                if (outcome == null) {
                    throw new AssertionError("unscripted batch target: " + t);
                }
                out.put(t, outcome.get());
            }
            return out;
        }

        private static String key(String userId, String type, String id) {
            return userId + "|" + type + "|" + (id == null ? " <type-level>" : id);
        }
    }
}
