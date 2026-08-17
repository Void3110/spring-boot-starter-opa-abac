package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.catalog.config.CatalogListAuthorizer;
import dev.dmitriikonovalov.example.catalog.config.SupervisedScopeClient;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.security.CatalogProvenanceMemo;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link CatalogListAuthorizer}: the fail-closed branches (Slice B4) <b>and</b> the two-leg
 * partitioned composition (ADR 0029, QA U25–U30, U32, U34).
 *
 * <p>The composition cases assert <b>which ids reach which slot</b> — the {@code scope} the whole page is
 * confined to, the anchor the residual-driving role is resolved on, and the {@code subtreeSpec} widening
 * arm. That triple <em>is</em> the slice's fail-open surface: if {@code supervised := S \ M} is skipped or
 * inverted, a doubly-reachable row gets judged by the vacuous-tag supervisor role instead of its tag-gated
 * membership role. U27 is its assertion. The actual row cut over real SQL is {@code SupervisedListIT}.
 */
class CatalogListAuthorizerTest {

    private final CatalogRepository catalogs = mock(CatalogRepository.class);
    private final RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);
    private final AbacQueryService queryService = mock(AbacQueryService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AbacQueryService> queryServiceProvider = mock(ObjectProvider.class);
    private final GovernedScopeResolver governedScopeResolver = mock(GovernedScopeResolver.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<GovernedScopeResolver> resolverProvider = mock(ObjectProvider.class);

    private final SupervisedScopeClient supervisedClient = mock(SupervisedScopeClient.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<SupervisedScopeClient> supervisedProvider = mock(ObjectProvider.class);

    private final CatalogListAuthorizer authorizer = new CatalogListAuthorizer(
            catalogs, supplier, queryServiceProvider, resolverProvider, supervisedProvider);

    private final Pageable pageable =
            PageRequest.of(0, 20, Sort.by("createdAt").ascending().and(Sort.by("id")));

    private static final UUID C1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID C2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID C4 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final RoleDefinition MEMBERSHIP_ROLE = new RoleDefinition(
            "owner", Map.of("provenance", "membership"), Map.of("catalog", List.of("READ", "WRITE")));
    private static final RoleDefinition SUPERVISOR_ROLE = new RoleDefinition(
            "supervisor-readonly", Map.of("provenance", "supervised"), Map.of("catalog", List.of("READ")));

    @BeforeEach
    void authenticate() {
        // The `_provenance` memo lives in request attributes (the repo's RequestContextHolder idiom),
        // so bind one: without it the write is a clean no-op and the memo assertions would pass
        // vacuously by reading an empty Optional back.
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        when(queryServiceProvider.getIfAvailable()).thenReturn(queryService);
        when(resolverProvider.getIfAvailable()).thenReturn(governedScopeResolver);
        when(supervisedProvider.getIfAvailable()).thenReturn(supervisedClient);
        AbacContext.Subject subject =
                new AbacContext.Subject("sub-1", List.of("catalog-editor"), Map.of());
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(subject));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void membership(UUID... ids) {
        when(governedScopeResolver.governedIds("sub-1", "catalog")).thenReturn(List.of(ids));
    }

    private void supervised(UUID... ids) {
        when(supervisedClient.supervisedIds("sub-1", "catalog")).thenReturn(List.of(ids));
    }

    private void roleOn(UUID anchor, RoleDefinition role) {
        when(supplier.lookup("sub-1", "catalog", anchor.toString())).thenReturn(Optional.of(role));
    }

    private void queryReturnsEmptyPage() {
        when(queryService.<CatalogEntity>findAuthorized(any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(pageable));
    }

    // --- fail-closed branches (B4, unchanged) --------------------------------------------------------

    @Test // U32 — no GovernedScopeResolver bean (e.g. demo profile) → empty page, query service untouched
    void noResolverBean_returnsEmptyPage_neverQueries() {
        when(resolverProvider.getIfAvailable()).thenReturn(null);

        Page<CatalogEntity> page = authorizer.readable(pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
        verify(supervisedClient, never()).supervisedIds(any(), any());
    }

    @Test // U32 — no AbacQueryService bean (the starter is off) → empty page, nothing fetched
    void noQueryServiceBean_returnsEmptyPage() {
        when(queryServiceProvider.getIfAvailable()).thenReturn(null);

        assertThat(authorizer.readable(pageable).getContent()).isEmpty();
        verify(governedScopeResolver, never()).governedIds(any(), any());
        verify(supervisedClient, never()).supervisedIds(any(), any());
    }

    @Test // U32 — unauthenticated → empty page, nothing touched
    void unauthenticated_returnsEmptyPage() {
        SecurityContextHolder.clearContext();

        Page<CatalogEntity> page = authorizer.readable(pageable);

        assertThat(page.getContent()).isEmpty();
        verify(resolverProvider, never()).getIfAvailable();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }

    @Test // U28 — BOTH scopes empty → empty page, never queries, never even resolves a role
    void bothScopesEmpty_returnsEmptyPage_neverQueries() {
        membership();
        supervised();

        Page<CatalogEntity> page = authorizer.readable(pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
        verify(supplier, never()).lookup(any(), any(), any());
    }

    @Test // membership-only outage with NO supervised leg → empty page, never queries (fail-closed, no 500)
    void roleSourceOutage_membershipOnly_returnsEmptyPage_neverQueries() {
        membership(C1);
        supervised();
        when(supplier.lookup("sub-1", "catalog", C1.toString()))
                .thenThrow(new RoleResolutionException("source unavailable"));

        Page<CatalogEntity> page = authorizer.readable(pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }

    @Test // an unresolved (absent) membership role with no supervised leg → empty page, dropped not defaulted
    void unresolvedMembershipRole_membershipOnly_returnsEmptyPage() {
        membership(C1);
        supervised();
        when(supplier.lookup("sub-1", "catalog", C1.toString())).thenReturn(Optional.empty());

        assertThat(authorizer.readable(pageable).getContent()).isEmpty();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }

    // --- U25 — membership only: BYTE-IDENTICAL to today (the non-regression assertion) ---------------

    @Test
    void membershipOnly_isTodaysCallUnchanged() {
        membership(C1, C2);
        supervised();
        roleOn(C1, MEMBERSHIP_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        // The role is resolved on the FIRST MEMBERSHIP id only.
        verify(supplier).lookup("sub-1", "catalog", C1.toString());
        verify(supplier, never()).lookup("sub-1", "catalog", C2.toString());
        Composition composition = captureComposition();
        assertThat(composition.scopeIds()).containsExactly(C1, C2);
        assertThat(composition.subtreeIds()).as("catalogs are roots — no widening arm").isNull();
        assertThat(composition.role()).isEqualTo(MEMBERSHIP_ROLE);
        assertThat(composition.action()).isEqualTo("catalog:list");
        assertThat(composition.resourceType()).isEqualTo("catalog");
    }

    @Test // no SupervisedScopeClient bean at all → byte-identical to today, no second leg
    void noSupervisedClientBean_isTodaysCallUnchanged() {
        when(supervisedProvider.getIfAvailable()).thenReturn(null);
        membership(C1);
        roleOn(C1, MEMBERSHIP_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        Composition composition = captureComposition();
        assertThat(composition.scopeIds()).containsExactly(C1);
        assertThat(composition.subtreeIds()).isNull();
        assertThat(composition.role()).isEqualTo(MEMBERSHIP_ROLE);
    }

    // --- U26 — a PURE supervisor: the supervised leg alone, with the SUPERVISOR role as context -------

    @Test
    void pureSupervisor_scopesToSupervisedWithTheSupervisorRole() {
        membership();
        supervised(C3, C4);
        roleOn(C3, SUPERVISOR_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        // The anchor is a SUPERVISED id — correct precisely because there are no membership rows to widen.
        verify(supplier).lookup("sub-1", "catalog", C3.toString());
        Composition composition = captureComposition();
        assertThat(composition.scopeIds()).containsExactly(C3, C4);
        assertThat(composition.subtreeIds())
                .as("a pure supervisor's ids ride `scope`; there is nothing to widen")
                .isNull();
        assertThat(composition.role()).isEqualTo(SUPERVISOR_ROLE);
    }

    // --- U27 — THE FAIL-OPEN EDGE: supervised := S \ M, and the anchor is a MEMBERSHIP id -------------

    @Test
    void dualHatted_reducesSupervisedByMembership_andAnchorsOnAMembershipId() {
        membership(C1);
        supervised(C1, C3); // C1 is reachable BOTH ways — membership must win
        roleOn(C1, MEMBERSHIP_ROLE);
        roleOn(C3, SUPERVISOR_ROLE); // must NOT be consulted
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        Composition composition = captureComposition();
        // C1 appears ONCE, in the union — never duplicated by the two legs.
        assertThat(composition.scopeIds()).containsExactly(C1, C3);
        // The widening arm carries the REDUCED set: C1 is NOT in it, so it is judged by the membership
        // residual. Had S \ M been skipped (or inverted), C1 would ride the vacuous supervisor arm.
        assertThat(composition.subtreeIds()).containsExactly(C3);
        assertThat(composition.subtreeIds()).doesNotContain(C1);
        // The residual-driving role comes from a MEMBERSHIP id — never from the union, never from C3.
        assertThat(composition.role()).isEqualTo(MEMBERSHIP_ROLE);
        verify(supplier).lookup("sub-1", "catalog", C1.toString());
        verify(supplier, never()).lookup("sub-1", "catalog", C3.toString());
    }

    @Test // the whole supervised set collapsing into membership leaves today's single-leg call exactly
    void supervisedFullyContainedInMembership_degradesToTheSingleLegCall() {
        membership(C1, C2);
        supervised(C2, C1);
        roleOn(C1, MEMBERSHIP_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        Composition composition = captureComposition();
        assertThat(composition.scopeIds()).containsExactly(C1, C2);
        assertThat(composition.subtreeIds()).as("S \\ M is empty → no widening arm at all").isNull();
        assertThat(composition.role()).isEqualTo(MEMBERSHIP_ROLE);
    }

    // --- U29 — the membership role source throws while supervised ids exist ---------------------------

    @Test
    void membershipRoleOutage_withSupervisedIds_keepsTheSupervisedLeg_noThrow() {
        membership(C1);
        supervised(C3);
        when(supplier.lookup("sub-1", "catalog", C1.toString()))
                .thenThrow(new RoleResolutionException("source unavailable"));
        roleOn(C3, SUPERVISOR_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        Composition composition = captureComposition();
        // The membership leg contributes NOTHING — C1 is gone from the scope entirely (never defaulted).
        assertThat(composition.scopeIds()).containsExactly(C3);
        assertThat(composition.subtreeIds()).isNull();
        assertThat(composition.role()).isEqualTo(SUPERVISOR_ROLE);
    }

    @Test // …and when the supervised leg has no authority either, the floor is still the empty page
    void bothLegsUnresolvable_returnsEmptyPage() {
        membership(C1);
        supervised(C3);
        when(supplier.lookup("sub-1", "catalog", C1.toString()))
                .thenThrow(new RoleResolutionException("source unavailable"));
        when(supplier.lookup("sub-1", "catalog", C3.toString())).thenReturn(Optional.empty());

        assertThat(authorizer.readable(pageable).getContent()).isEmpty();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }

    // --- the mid-request revocation race (found at the ★ gate; see STATUS-05) -----------------------

    @Test // a membership revoked between the two calls must NOT let the supervisor role judge the union
    void membershipAnchorResolvingASupervisedRole_dropsTheMembershipLeg() {
        // C1 and C2 are governed at scope-read time; between that read and the role resolve the membership
        // on C1 is revoked, so the user-service's ordered fallthrough answers with the SYNTHESIZED
        // supervisor role. Letting it drive the residual would apply its VACUOUS tag requirement to C2 —
        // a tag-gated membership row it never earned.
        membership(C1, C2);
        supervised(C3);
        roleOn(C1, SUPERVISOR_ROLE); // the stale-membership answer
        roleOn(C3, SUPERVISOR_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        Composition composition = captureComposition();
        assertThat(composition.scopeIds())
                .as("the stale membership leg is dropped entirely — C2 is never judged by a role it "
                        + "did not earn")
                .containsExactly(C3);
        assertThat(composition.subtreeIds()).isNull();
        assertThat(composition.role()).isEqualTo(SUPERVISOR_ROLE);
    }

    @Test // …and with no supervised leg to fall back to, the floor is the empty page
    void membershipAnchorResolvingASupervisedRole_withNoSupervisedLeg_returnsEmptyPage() {
        membership(C1, C2);
        supervised();
        roleOn(C1, SUPERVISOR_ROLE);

        assertThat(authorizer.readable(pageable).getContent()).isEmpty();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }

    @Test // an UNSTAMPED membership role (a role source that does not stamp provenance) is unaffected
    void unstampedMembershipRoleIsUnaffected() {
        RoleDefinition unstamped =
                new RoleDefinition("owner", Map.of(), Map.of("catalog", List.of("READ", "WRITE")));
        membership(C1);
        supervised(C3);
        roleOn(C1, unstamped);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        Composition composition = captureComposition();
        assertThat(composition.scopeIds()).containsExactly(C1, C3);
        assertThat(composition.subtreeIds()).containsExactly(C3);
        assertThat(composition.role()).isEqualTo(unstamped);
    }

    // --- U30 — the supervised source fails while membership ids exist → membership-only ---------------

    @Test
    void supervisedSourceFailure_degradesToMembershipOnly_neverWider() {
        membership(C1, C2);
        supervised(); // the client already fails closed to an empty list on every failure class (U19–U23)
        roleOn(C1, MEMBERSHIP_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        Composition composition = captureComposition();
        assertThat(composition.scopeIds()).containsExactly(C1, C2);
        assertThat(composition.subtreeIds()).isNull();
        assertThat(composition.role()).isEqualTo(MEMBERSHIP_ROLE);
    }

    // --- ADR 0033 — what the `_provenance` memo carries on each branch (I2's degrade cells) -----------
    //
    // The memo IS the list's label. These assert what it holds where the branch is hard to reach from
    // an IT: the advice's mapping of memo → label is CatalogProvenanceAdviceTest's (U7).

    @Test // a mixed page: the memo names exactly the supervised ids, so those rows label `supervised`
    void memoCarriesTheSupervisedIdsOnAMixedPage() {
        membership(C1);
        supervised(C3);
        roleOn(C1, MEMBERSHIP_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        assertThat(CatalogProvenanceMemo.read()).contains(Set.of(C3));
    }

    @Test // a plain member: PRESENT and EMPTY — "no supervised leg here", not "never computed"
    void memoIsPresentButEmptyForAPlainMember() {
        membership(C1, C2);
        supervised();
        roleOn(C1, MEMBERSHIP_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        // The distinction the whole absence contract rests on: isPresent() is true, the set is empty.
        // If the write ever moved back inside auditSupervisedRead — whose first early return fires on an
        // empty supervised set — this is the assertion that catches it.
        assertThat(CatalogProvenanceMemo.read()).isPresent();
        assertThat(CatalogProvenanceMemo.read().orElseThrow()).isEmpty();
    }

    @Test // supervised-source outage: an empty set, so the surviving membership rows label `member`
    void memoIsEmptyWhenTheSupervisedSourceIsDown() {
        membership(C1, C2);
        supervised(); // the client fails closed to an empty list on every failure class (U19–U23)
        roleOn(C1, MEMBERSHIP_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        assertThat(CatalogProvenanceMemo.read()).contains(Set.of());
    }

    @Test // membership-role outage: the page degraded to supervised-only, and the memo is exactly those
    void memoCoversTheWholePageWhenTheMembershipLegDropped() {
        membership(C1);
        supervised(C3);
        when(supplier.lookup("sub-1", "catalog", C1.toString()))
                .thenThrow(new RoleResolutionException("source unavailable"));
        roleOn(C3, SUPERVISOR_ROLE);
        queryReturnsEmptyPage();

        authorizer.readable(pageable);

        // scope == {C3} here, so every row on the page is supervised — and the memo says so.
        assertThat(CatalogProvenanceMemo.read()).contains(Set.of(C3));
    }

    // --- U34 — the PRECONDITION that makes admitting supervised rows through subtreeSpec correct -------

    @Test
    void supervisorRolesResidualIsUnconditional_thePreconditionForTheWideningArm() {
        // Admitting supervised rows through `subtreeSpec` is correct ONLY because the supervisor role's
        // residual is unconditional: it grants the coarse READ token with NO required tags, so
        // `data.catalog.filter` folds to the type-eq tautology → ALLOW_ALL. This asserts the role SHAPE
        // that makes that hold; the policy-level measurement is recorded in STATUS-05, and the shipped
        // corpus proves it in catalog.rego's `filter_tags_satisfied` (no required_tags → vacuously true).
        // If a later slice gives the supervisor role a tag requirement, THIS test fails and T5's
        // composition must change with it — that is the coupling it exists to make visible.
        assertThat(SUPERVISOR_ROLE.requiredTags())
                .as("a tag requirement would make the vacuous widening arm wrong")
                .isNullOrEmpty();
        assertThat(SUPERVISOR_ROLE.deniedActions()).isNullOrEmpty();
        assertThat(SUPERVISOR_ROLE.permissions()).containsExactly(Map.entry("catalog", List.of("READ")));
    }

    // --- composition capture -------------------------------------------------------------------------

    /** What the authorizer handed the shipped 5-arg {@code findAuthorized}. */
    private record Composition(
            List<UUID> scopeIds,
            List<UUID> subtreeIds,
            RoleDefinition role,
            String action,
            String resourceType) {}

    @SuppressWarnings("unchecked")
    private Composition captureComposition() {
        ArgumentCaptor<Specification<CatalogEntity>> scope = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Specification<CatalogEntity>> subtree = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<AbacContext> context = ArgumentCaptor.forClass(AbacContext.class);
        verify(queryService).findAuthorized(
                eq(catalogs), scope.capture(), context.capture(), subtree.capture(), eq(pageable));
        AbacContext ctx = context.getValue();
        return new Composition(
                idsOf(scope.getValue()),
                idsOf(subtree.getValue()),
                ctx.roleDefinition(),
                ctx.action(),
                ctx.resource().type());
    }

    /**
     * Invoke an {@code id IN (…)} Specification against a mocked criteria API and report the captured ids
     * — the same probe style {@code HttpGovernedScopeResolverTest} uses. {@code null} in, {@code null} out.
     */
    @SuppressWarnings("unchecked")
    private static List<UUID> idsOf(Specification<CatalogEntity> spec) {
        if (spec == null) {
            return null;
        }
        Root<CatalogEntity> root = mock(Root.class);
        Path<Object> idPath = mock(Path.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        AtomicReference<Object> captured = new AtomicReference<>();
        when(root.get("id")).thenReturn(idPath);
        when(idPath.in(any(Collection.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return mock(Predicate.class);
        });

        spec.toPredicate(root, query, cb);

        return List.copyOf((Collection<UUID>) captured.get());
    }
}
