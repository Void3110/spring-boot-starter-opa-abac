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
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
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

    // --- helpers -------------------------------------------------------------

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
}
