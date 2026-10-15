package dev.dmitriikonovalov.opaabac.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * U7 — the request memo over the tool-gate's ceiling: the single-lookup semantics of the role-definition
 * memo (ADR 0023), keyed by {@code (subject, resourceType)}.
 */
class MemoizingPrincipalCeilingSupplierTest {

    private static final RoleDefinition CEILING = new RoleDefinition(
            "type-level", Map.of(), Map.of("product", List.of("READ")), Map.of(), Map.of(), null);

    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
    }

    private PrincipalCeilingSupplier counting(PrincipalCeilingSupplier answer) {
        return (subject, type) -> {
            calls.incrementAndGet();
            return answer.lookup(subject, type);
        };
    }

    @Test
    void twoLookupsOfOneKeyCallTheDelegateOnce() {
        PrincipalCeilingSupplier memo =
                new MemoizingPrincipalCeilingSupplier(counting((subject, type) -> Optional.of(CEILING)));

        assertThat(memo.lookup("alice", "product")).contains(CEILING);
        assertThat(memo.lookup("alice", "product")).contains(CEILING);
        assertThat(calls).hasValue(1);
    }

    @Test // the key is (subject, resourceType): another caller or another type is never served this answer
    void aDifferentSubjectOrTypeIsADistinctKey() {
        PrincipalCeilingSupplier memo =
                new MemoizingPrincipalCeilingSupplier(counting((subject, type) -> Optional.of(CEILING)));

        memo.lookup("alice", "product");
        memo.lookup("bob", "product");
        memo.lookup("alice", "catalog");
        assertThat(calls).hasValue(3);
    }

    @Test // the authoritative no-role is an answer, memoized like any other
    void anEmptyResultIsMemoized() {
        PrincipalCeilingSupplier memo =
                new MemoizingPrincipalCeilingSupplier(counting((subject, type) -> Optional.empty()));

        assertThat(memo.lookup("alice", "product")).isEmpty();
        assertThat(memo.lookup("alice", "product")).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test // the family outage is memoized and RE-THROWN — never a mixed snapshot within one request
    void aFamilyOutageIsMemoizedAndRethrownWithoutASecondDelegateCall() {
        RoleResolutionException outage = new RoleResolutionException("role source unavailable");
        PrincipalCeilingSupplier memo = new MemoizingPrincipalCeilingSupplier(counting((subject, type) -> {
            throw outage;
        }));

        assertThatThrownBy(() -> memo.lookup("alice", "product")).isSameAs(outage);
        assertThatThrownBy(() -> memo.lookup("alice", "product")).isSameAs(outage);
        assertThat(calls).hasValue(1);
    }

    @Test // only the contractual outage is memoized: any other failure propagates and is retried
    void aNonFamilyExceptionPropagatesAndIsNotMemoized() {
        PrincipalCeilingSupplier memo = new MemoizingPrincipalCeilingSupplier(counting((subject, type) -> {
            throw new IllegalStateException("bug");
        }));

        assertThatThrownBy(() -> memo.lookup("alice", "product")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> memo.lookup("alice", "product")).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(2);
    }

    @Test // outside a request there is nothing to scope a memo to: a clean pass-through
    void withoutARequestEveryLookupReachesTheDelegate() {
        RequestContextHolder.resetRequestAttributes();
        PrincipalCeilingSupplier memo =
                new MemoizingPrincipalCeilingSupplier(counting((subject, type) -> Optional.of(CEILING)));

        memo.lookup("alice", "product");
        memo.lookup("alice", "product");
        assertThat(calls).hasValue(2);
    }

    @Test // layer-2 — two decorated suppliers in one request never serve each other's answers
    void twoDecoratorsInOneRequestKeepSeparateMemos() {
        RoleDefinition other = new RoleDefinition(
                "other", Map.of(), Map.of("product", List.of("WRITE")), Map.of(), Map.of(), null);
        PrincipalCeilingSupplier first = new MemoizingPrincipalCeilingSupplier((subject, type) -> Optional.of(CEILING));
        PrincipalCeilingSupplier second = new MemoizingPrincipalCeilingSupplier((subject, type) -> Optional.of(other));

        assertThat(first.lookup("alice", "product")).contains(CEILING);
        assertThat(second.lookup("alice", "product")).contains(other);
    }

    @Test // the memo dies with the request: a new request asks again (a revocation lands next request)
    void aNewRequestAsksAgain() {
        PrincipalCeilingSupplier memo =
                new MemoizingPrincipalCeilingSupplier(counting((subject, type) -> Optional.of(CEILING)));

        memo.lookup("alice", "product");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        memo.lookup("alice", "product");
        assertThat(calls).hasValue(2);
    }
}
