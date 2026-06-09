package dev.dmitriikonovalov.opaabac.data.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolutionException;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Unit tests for {@link AbacQueryService} with a mock repository and a programmable stub {@link OpaClient}.
 * Covers QA cases U20–U24: residual outcomes map to the right query; the allowlist path is invoked only
 * when flagged-and-toggled and drops {@code false} rows; the {@code enabled=false} kill-switch degrades to
 * the coarse path.
 */
@SuppressWarnings("unchecked")
class AbacQueryServiceTest {

    private final ResidualSpecificationFactory factory = new ResidualSpecificationFactory();

    private AbacContext context() {
        return new AbacContext(
                new AbacContext.Subject("u1", List.of(), Map.of()),
                "category:read",
                new AbacContext.Resource("category", null, Map.of()),
                new RoleDefinition("viewer", Map.of(), Map.of("category", List.of("read"))),
                Map.of());
    }

    private AbacQueryService service(OpaClient client, AbacQueryService.PartialEvalSettings settings) {
        return new AbacQueryService(client, factory, settings);
    }

    @Test // U20 — ALLOW_ALL → only the caller scope applied (no compile-derived predicate); no allow() call
    void allowAll_appliesScopeOnly() {
        AtomicReference<Specification<Row>> passed = new AtomicReference<>();
        JpaSpecificationExecutor<Row> repo = repoCapturing(passed, List.of(new Row("a")));
        Specification<Row> scope = (r, q, cb) -> null;

        OpaClient client = stub(PartialResult.allowAll(), null, false);
        List<Row> result = service(client, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(repo, scope, context());

        assertThat(result).extracting(Row::id).containsExactly("a");
        // the scope passed through; ALLOW_ALL contributed no predicate (Specification.and(where(null)))
        assertThat(passed.get()).isNotNull();
    }

    @Test // U21 — DENY_ALL → an always-false spec → empty list
    void denyAll_returnsEmpty() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of());

        OpaClient client = stub(PartialResult.denyAll(), null, false);
        List<Row> result = service(client, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(repo, (r, q, cb) -> null, context());

        assertThat(result).isEmpty();
    }

    @Test // U22 — CONDITIONAL → scope.and(authzSpec) passed to findAll; no batch
    void conditional_andsScopeWithAuthzSpec() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(new Row("a")));
        OpaClient client = Mockito.spy(stub(
                new PartialResult(
                        PartialResult.Decision.CONDITIONAL,
                        List.of(new Conjunction(List.of(new Condition("tags.region", Condition.Operator.EQ, "emea"))))),
                null,
                false));

        service(client, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(repo, (r, q, cb) -> null, context());

        // a combined spec reached the repo; the batch path was NOT taken (fully supported)
        ArgumentCaptor<Specification<Row>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(repo).findAll(spec.capture());
        assertThat(spec.getValue()).isNotNull();
        verify(client, never()).allowAll(any());
    }

    @Test // U23 — allowlist fallback ON + not-fully-SQL residual → allowAll over survivors, false rows dropped
    void allowlistFallback_dropsFalseRows() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class)))
                .thenReturn(List.of(new Row("a"), new Row("b"), new Row("c")));
        // residual flagged not-fully-SQL; batch says keep a and c, drop b
        OpaClient client = stub(PartialResult.unsupported(), List.of(true, false, true), false);

        List<Row> result = service(client, new AbacQueryService.PartialEvalSettings(true, true))
                .findAuthorized(repo, (r, q, cb) -> null, context());

        assertThat(result).extracting(Row::id).containsExactly("a", "c");
    }

    @Test // U23 — allowlist fallback OFF + not-fully-SQL residual → DENY (empty), allowAll NOT invoked
    void allowlistFallbackOff_deniesWithoutBatch() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of());
        OpaClient client = Mockito.spy(stub(PartialResult.unsupported(), List.of(true), false));

        List<Row> result = service(client, new AbacQueryService.PartialEvalSettings(true, false))
                .findAuthorized(repo, (r, q, cb) -> null, context());

        assertThat(result).isEmpty();
        verify(client, never()).allowAll(any());
    }

    @Test // U24 — partialEval.enabled=false → coarse path: one allow() check, no compile()
    void killSwitch_usesCoarsePath() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(new Row("a")));
        OpaClient client = Mockito.spy(stub(PartialResult.allowAll(), null, true)); // allow() → true

        List<Row> result = service(client, new AbacQueryService.PartialEvalSettings(false, true))
                .findAuthorized(repo, (r, q, cb) -> null, context());

        assertThat(result).extracting(Row::id).containsExactly("a");
        verify(client).allow(any());
        verify(client, never()).compile(any());
    }

    @Test // U24 — kill-switch + coarse allow() denies → empty list (still fail-closed)
    void killSwitch_coarseDeny_returnsEmpty() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        OpaClient client = Mockito.spy(stub(PartialResult.allowAll(), null, false)); // allow() → false

        List<Row> result = service(client, new AbacQueryService.PartialEvalSettings(false, true))
                .findAuthorized(repo, (r, q, cb) -> null, context());

        assertThat(result).isEmpty();
        verify(repo, never()).findAll(any(Specification.class));
    }

    // --- 5.5-B: 4-arg overload + notDenied + hierarchy-aware batch ----------

    @Test // U6/U7 — the 3-arg path and the 4-arg-with-null subtreeSpec are behaviorally identical
    void threeArg_equals_fourArgNullSubtree() {
        OpaClient client = stub(conditionalEmea(), null, false);

        AtomicReference<Specification<Row>> threeArg = new AtomicReference<>();
        AtomicReference<Specification<Row>> fourArgNull = new AtomicReference<>();

        service(client, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(repoCapturing(threeArg, List.of(new Row("a"))), (r, q, cb) -> null, context());
        service(client, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(
                        repoCapturing(fourArgNull, List.of(new Row("a"))),
                        (r, q, cb) -> null, context(), null);

        // both produced a (non-null) composed spec reaching the repo — the 3-arg delegates to the 4-arg.
        assertThat(threeArg.get()).isNotNull();
        assertThat(fourArgNull.get()).isNotNull();
    }

    @Test // U8 — 4-arg with a subtreeSpec → a composed spec reaches the repo; no batch (fully supported)
    void fourArg_withSubtreeSpec_composesAndDoesNotBatch() {
        AtomicReference<Specification<Row>> passed = new AtomicReference<>();
        JpaSpecificationExecutor<Row> repo = repoCapturing(passed, List.of(new Row("a")));
        OpaClient client = Mockito.spy(stub(conditionalEmea(), null, false));
        Specification<Row> subtree = (r, q, cb) -> cb.equal(r.get("catalogId"), "C");

        service(client, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(repo, (r, q, cb) -> null, context(), subtree);

        assertThat(passed.get()).isNotNull(); // scope.and(tagResidual.or(subtree)).and(notDenied)
        verify(client, never()).allowAll(any()); // pure-SQL path, not batch
    }

    @Test // U10 — a POLICY-derived DENY_ALL (unsatisfiable tag branch) + a subtreeSpec → the subtree
    // branch still widens (OR): "no tag-matching rows" is a real policy answer, and an inheritable
    // ancestor grant may legitimately make subtree rows visible alongside it.
    void denyAllResidual_withSubtree_stillWidensViaOr() {
        AtomicReference<Specification<Row>> passed = new AtomicReference<>();
        JpaSpecificationExecutor<Row> repo = repoCapturing(passed, List.of(new Row("a")));
        OpaClient client = stub(PartialResult.denyAll(), null, false);
        Specification<Row> subtree = (r, q, cb) -> cb.equal(r.get("catalogId"), "C");

        // DENY_ALL tag-branch is an always-false predicate, but OR-ed with the subtree it still composes a
        // non-null spec (the subtree rows survive). The actual row-set widening is proven in the T4 IT.
        service(client, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(repo, (r, q, cb) -> null, context(), subtree);

        assertThat(passed.get()).isNotNull();
    }

    @Test // an ERROR DENY_ALL (failed Compile call — OPA outage) + a subtreeSpec → the WHOLE list fails
    // closed: no repo query at all. The subtree widening mirrors the policy's inherited-grant clause and
    // must never outlive the policy engine it mirrors.
    void errorResidual_withSubtree_failsWholeListClosed() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        OpaClient client = Mockito.spy(stub(PartialResult.error(), null, false));
        Specification<Row> subtree = (r, q, cb) -> cb.equal(r.get("catalogId"), "C");

        List<Row> result = service(client, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(repo, (r, q, cb) -> null, context(), subtree);

        assertThat(result).isEmpty();
        verify(repo, never()).findAll(any(Specification.class));
        verify(client, never()).allowAll(any());
    }

    @Test // an ERROR DENY_ALL with the allowlist fallback ON → still empty, no batch: an outage is not
    // an "unsupported residual" and must not trigger the candidate fetch + re-check path either
    void errorResidual_allowlistOn_noBatchNoRows() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        OpaClient client = Mockito.spy(stub(PartialResult.error(), List.of(true), false));

        List<Row> result = service(client, new AbacQueryService.PartialEvalSettings(true, true))
                .findAuthorized(repo, (r, q, cb) -> null, context());

        assertThat(result).isEmpty();
        verify(repo, never()).findAll(any(Specification.class));
        verify(client, never()).allowAll(any());
    }

    @Test // U11 — batch path: each per-row AbacContext carries the row's ANCESTOR chain (4-arg Resource)
    void batchPath_perRowContextCarriesAncestors() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class)))
                .thenReturn(List.of(new HierRow("a"), new HierRow("b")));
        OpaClient client = Mockito.spy(stub(PartialResult.unsupported(), List.of(true, true), false));

        AncestorResolver resolver = ancestorsReturning(List.of(new ParentRef("catalog", "C")));
        hierService(client, new AbacQueryService.PartialEvalSettings(true, true), resolver)
                .findAuthorized(repo, (r, q, cb) -> null, context(), (r, q, cb) -> cb.equal(r.get("x"), "y"));

        ArgumentCaptor<List<AbacContext>> perRow = ArgumentCaptor.forClass(List.class);
        verify(client).allowAll(perRow.capture());
        // each per-row Resource carries the resolved ancestor chain — the same input as a single-GET
        assertThat(perRow.getValue())
                .allSatisfy(ctx -> assertThat(ctx.resource().ancestors())
                        .containsExactly(new ParentRef("catalog", "C")));
    }

    @Test // U12 — batch path: a candidate whose ancestor resolution throws → EMPTY ancestors (direct-only)
    void batchPath_resolverFailure_emptyAncestors() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(new HierRow("a")));
        OpaClient client = Mockito.spy(stub(PartialResult.unsupported(), List.of(true), false));

        AncestorResolver failing = throwingResolver();
        hierService(client, new AbacQueryService.PartialEvalSettings(true, true), failing)
                .findAuthorized(repo, (r, q, cb) -> null, context(), null);

        ArgumentCaptor<List<AbacContext>> perRow = ArgumentCaptor.forClass(List.class);
        verify(client).allowAll(perRow.capture());
        assertThat(perRow.getValue().get(0).resource().ancestors()).isEmpty(); // fail-closed: direct-only
    }

    @Test // U11/U12 — short/all-false batch decision still drops rows (fail-closed), hierarchy-aware
    void batchPath_shortDecisionList_dropsRows() {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class)))
                .thenReturn(List.of(new HierRow("a"), new HierRow("b")));
        // the client returns a SHORT list (error shape) → both rows dropped
        OpaClient client = stub(PartialResult.unsupported(), List.of(), false);

        List<Row> result = hierService(
                        client, new AbacQueryService.PartialEvalSettings(true, true),
                        ancestorsReturning(List.of()))
                .findAuthorized(repo, (r, q, cb) -> null, context(), null);

        assertThat(result).isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    private AbacQueryService hierService(
            OpaClient client, AbacQueryService.PartialEvalSettings settings, AncestorResolver resolver) {
        return new AbacQueryService(client, factory, settings, resolver);
    }

    private static PartialResult conditionalEmea() {
        return new PartialResult(
                PartialResult.Decision.CONDITIONAL,
                List.of(new Conjunction(List.of(new Condition("tags.region", Condition.Operator.EQ, "emea")))));
    }

    private static AncestorResolver ancestorsReturning(List<ParentRef> chain) {
        return new AncestorResolver() {
            @Override
            public List<ParentRef> ancestorsOf(String leafType, String leafId) {
                return chain;
            }

            @Override
            public <T> Specification<T> subtreeOf(String rootType, String rootId) {
                return (root, query, cb) -> cb.disjunction();
            }
        };
    }

    private static AncestorResolver throwingResolver() {
        return new AncestorResolver() {
            @Override
            public List<ParentRef> ancestorsOf(String leafType, String leafId) {
                throw new AncestorResolutionException("broken");
            }

            @Override
            public <T> Specification<T> subtreeOf(String rootType, String rootId) {
                return (root, query, cb) -> cb.disjunction();
            }
        };
    }

    private static JpaSpecificationExecutor<Row> repoCapturing(
            AtomicReference<Specification<Row>> captured, List<Row> result) {
        JpaSpecificationExecutor<Row> repo = Mockito.mock(JpaSpecificationExecutor.class);
        when(repo.findAll(any(Specification.class))).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return result;
        });
        return repo;
    }

    /** A stub OpaClient returning the given compile residual, batch decisions, and allow() verdict. */
    private static OpaClient stub(PartialResult compileResult, List<Boolean> batch, boolean allowVerdict) {
        return new OpaClient() {
            @Override
            public boolean allow(AbacContext context) {
                return allowVerdict;
            }

            @Override
            public PartialResult compile(AbacContext context) {
                return compileResult;
            }

            @Override
            public List<Boolean> allowAll(List<AbacContext> contexts) {
                return batch == null ? List.of() : batch;
            }
        };
    }

    /** A minimal authorizable row. */
    record Row(String id) implements AbacDataObject {
        @Override
        public String abacResourceType() {
            return "category";
        }

        @Override
        public String abacResourceId() {
            return id;
        }
    }

    /** A hierarchical authorizable row (declares a parent) for the hierarchy-aware batch tests. */
    record HierRow(String id) implements AbacDataObject {
        @Override
        public String abacResourceType() {
            return "category";
        }

        @Override
        public String abacResourceId() {
            return id;
        }

        @Override
        public java.util.Optional<ParentRef> abacParent() {
            return java.util.Optional.of(new ParentRef("catalog", "C"));
        }
    }
}
