package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The {@code lookupAll} default method (ADR 0024; QA case U8): strict completeness on the happy
 * path, the empty-set short-circuit, whole-batch abort on any single throw — and the
 * {@code @FunctionalInterface} proof in miniature (every supplier here is a lambda).
 */
class RoleDefinitionSupplierLookupAllTest {

    private static final RoleDefinition ROLE = new RoleDefinition("role-a", Map.of(), Map.of());
    private static final ResolveTarget CAT_1 = new ResolveTarget("catalog", "c-1");
    private static final ResolveTarget CAT_2 = new ResolveTarget("catalog", "c-2");
    private static final ResolveTarget PROD = new ResolveTarget("product", "p-1");

    @Test // U8 — exactly one two-state entry per requested target
    void defaultLoopIsStrictlyComplete() {
        RoleDefinitionSupplier supplier = (userId, type, id) ->
                "c-1".equals(id) ? Optional.of(ROLE) : Optional.empty();

        Map<ResolveTarget, Optional<RoleDefinition>> result =
                supplier.lookupAll("u", Set.of(CAT_1, CAT_2, PROD));

        assertThat(result).containsOnlyKeys(CAT_1, CAT_2, PROD);
        assertThat(result.get(CAT_1)).contains(ROLE);
        assertThat(result.get(CAT_2)).isEmpty();
        assertThat(result.get(PROD)).isEmpty();
    }

    @Test // U8 — order-independence: any iteration order of the same set yields the same map
    void defaultLoopIsOrderIndependent() {
        RoleDefinitionSupplier supplier = (userId, type, id) -> Optional.of(ROLE);

        Set<ResolveTarget> forward = new LinkedHashSet<>(Set.of(CAT_1, CAT_2));
        Set<ResolveTarget> backward = new LinkedHashSet<>();
        backward.add(CAT_2);
        backward.add(CAT_1);

        assertThat(supplier.lookupAll("u", forward)).isEqualTo(supplier.lookupAll("u", backward));
    }

    @Test // U8 — empty set → empty map with ZERO lookups
    void emptySetShortCircuits() {
        AtomicInteger calls = new AtomicInteger();
        RoleDefinitionSupplier supplier = (userId, type, id) -> {
            calls.incrementAndGet();
            return Optional.of(ROLE);
        };

        assertThat(supplier.lookupAll("u", Set.of())).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test // U8 — any single throw aborts the WHOLE batch (no partial map escapes)
    void singleThrowAbortsTheWholeBatch() {
        RoleResolutionException outage = new RoleResolutionException("source down");
        RoleDefinitionSupplier supplier = (userId, type, id) -> {
            if ("c-2".equals(id)) {
                throw outage;
            }
            return Optional.of(ROLE);
        };

        assertThatThrownBy(() -> supplier.lookupAll("u", Set.of(CAT_1, CAT_2, PROD)))
                .isSameAs(outage);
    }

    @Test // U8 — the returned map is immutable (a caller cannot bend strict completeness after the fact)
    void returnedMapIsImmutable() {
        RoleDefinitionSupplier supplier = (userId, type, id) -> Optional.of(ROLE);

        Map<ResolveTarget, Optional<RoleDefinition>> result = supplier.lookupAll("u", Set.of(CAT_1));

        assertThatThrownBy(() -> result.put(CAT_2, Optional.empty()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test // ResolveTarget value semantics are the batch key
    void resolveTargetHasValueSemantics() {
        assertThat(new ResolveTarget("catalog", "c-1")).isEqualTo(CAT_1);
        assertThat(new ResolveTarget("category", "c-1")).isNotEqualTo(CAT_1);
        assertThatThrownBy(() -> new ResolveTarget(null, "c-1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolveTarget("catalog", null)).isInstanceOf(NullPointerException.class);
    }
}
