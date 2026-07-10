package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

    /** A scriptable counting delegate: one programmable answer per key, calls counted per key. */
    private static final class CountingSupplier implements RoleDefinitionSupplier {

        private final Map<String, Supplier<Optional<RoleDefinition>>> answers = new HashMap<>();
        private final Map<String, Integer> counts = new HashMap<>();

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

        private static String key(String userId, String type, String id) {
            return userId + "|" + type + "|" + (id == null ? " <type-level>" : id);
        }
    }
}
