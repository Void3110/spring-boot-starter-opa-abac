package dev.dmitriikonovalov.opaabac.data.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests for {@link ResidualSpecificationFactory} — the residual → Criteria translation, captured via
 * a mock {@link CriteriaBuilder}/{@link Root} (no database). Covers QA cases U13–U19: {@code ALLOW_ALL} →
 * no predicate, {@code DENY_ALL} → disjunction, and each operator's {@code function(...)}/predicate shape.
 * The actual row-set behavior is proven by {@link ResidualSpecificationIT} against real Postgres.
 */
@SuppressWarnings("unchecked")
class ResidualSpecificationFactoryTest {

    private final ResidualSpecificationFactory factory = new ResidualSpecificationFactory();

    private Root<Object> root;
    private CriteriaQuery<?> query;
    private CriteriaBuilder cb;
    private Path<Object> tagsPath;

    @BeforeEach
    void setUp() {
        root = mock(Root.class);
        query = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        tagsPath = mock(Path.class);
        when(root.get("tags")).thenReturn(tagsPath);
    }

    @Test // U13 — ALLOW_ALL → no predicate contributed (Specification.unrestricted())
    void allowAll_contributesNoPredicate() {
        Specification<Object> spec = factory.from(PartialResult.allowAll());
        assertThat(spec.toPredicate(root, query, cb)).isNull();
        verify(cb, never()).disjunction();
    }

    @Test // U14 — DENY_ALL → an always-false predicate (cb.disjunction())
    void denyAll_isAlwaysFalse() {
        Predicate alwaysFalse = mock(Predicate.class);
        when(cb.disjunction()).thenReturn(alwaysFalse);

        Specification<Object> spec = factory.from(PartialResult.denyAll());
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(alwaysFalse);
        verify(cb).disjunction();
    }

    @Test // U14b — a null residual is treated as DENY_ALL (fail-closed)
    void nullResidual_isAlwaysFalse() {
        Predicate alwaysFalse = mock(Predicate.class);
        when(cb.disjunction()).thenReturn(alwaysFalse);

        Predicate result = factory.from(null).toPredicate(root, query, cb);

        assertThat(result).isSameAs(alwaysFalse);
    }

    @Test // U15 — EQ on tags.region → jsonb_extract_path_text(tags,'region') = 'emea'
    void eq_onTag_usesExtractTextEquals() {
        Expression<String> extract = mock(Expression.class);
        when(cb.function(eq("jsonb_extract_path_text"), eq(String.class), any(), any())).thenReturn(extract);
        Predicate eqPred = mock(Predicate.class);
        when(cb.equal(eq(extract), eq("emea"))).thenReturn(eqPred);
        // CONDITIONAL needs at least the and()/or() combinators stubbed; return the inner predicate.
        when(cb.and(any(Predicate[].class))).thenReturn(eqPred);
        when(cb.or(any(Predicate[].class))).thenReturn(eqPred);

        PartialResult residual = conditional(new Condition("tags.region", Condition.Operator.EQ, "emea"));
        factory.from(residual).toPredicate(root, query, cb);

        verify(cb).function(eq("jsonb_extract_path_text"), eq(String.class), eq(tagsPath), any());
        verify(cb).equal(extract, "emea");
    }

    @Test // U16 — IN on tags.region → jsonb_extract_path_text(...) IN ('emea','amer')
    void in_onTag_usesExtractTextIn() {
        Expression<String> extract = mock(Expression.class);
        when(cb.function(eq("jsonb_extract_path_text"), eq(String.class), any(), any())).thenReturn(extract);
        Predicate inPred = mock(Predicate.class);
        when(extract.in(any(List.class))).thenReturn(inPred);
        when(cb.and(any(Predicate[].class))).thenReturn(inPred);
        when(cb.or(any(Predicate[].class))).thenReturn(inPred);

        PartialResult residual =
                conditional(new Condition("tags.region", Condition.Operator.IN, List.of("emea", "amer")));
        factory.from(residual).toPredicate(root, query, cb);

        verify(cb).function(eq("jsonb_extract_path_text"), eq(String.class), eq(tagsPath), any());
        verify(extract).in(List.of("emea", "amer"));
    }

    @Test // U17 — CONTAINS on an array tag → jsonb_exists(jsonb_extract_path(tags,'region'),'emea')
    void contains_onArrayTag_usesExistenceOp() {
        Expression<Object> arrayAtKey = mock(Expression.class);
        when(cb.function(eq("jsonb_extract_path"), eq(Object.class), any(), any())).thenReturn(arrayAtKey);
        Expression<Boolean> exists = mock(Expression.class);
        when(cb.function(eq("jsonb_exists"), eq(Boolean.class), any(), any())).thenReturn(exists);
        Predicate isTrue = mock(Predicate.class);
        when(cb.isTrue(exists)).thenReturn(isTrue);
        when(cb.and(any(Predicate[].class))).thenReturn(isTrue);
        when(cb.or(any(Predicate[].class))).thenReturn(isTrue);

        PartialResult residual =
                conditional(new Condition("tags.region", Condition.Operator.CONTAINS, "emea"));
        factory.from(residual).toPredicate(root, query, cb);

        verify(cb).function(eq("jsonb_exists"), eq(Boolean.class), any(), any());
        verify(cb).isTrue(exists);
    }

    @Test // U18 — an intrinsic path (categoryId) → root.get("categoryId") comparison, not JSONB
    void intrinsic_usesRootColumn() {
        Path<Object> column = mock(Path.class);
        when(root.get("categoryId")).thenReturn(column);
        Predicate eqPred = mock(Predicate.class);
        when(cb.equal(eq(column), any())).thenReturn(eqPred);
        when(cb.and(any(Predicate[].class))).thenReturn(eqPred);
        when(cb.or(any(Predicate[].class))).thenReturn(eqPred);

        PartialResult residual =
                conditional(new Condition("categoryId", Condition.Operator.EQ, "cat-1"));
        factory.from(residual).toPredicate(root, query, cb);

        verify(root).get("categoryId");
        verify(cb).equal(column, "cat-1");
        // no JSONB function for an intrinsic column
        verify(cb, never()).function(eq("jsonb_extract_path_text"), any(), any(), any());
    }

    @Test // U19 — DNF (two Conjunctions) → OR( AND(...), AND(...) ) structure
    void dnf_buildsOrOfAnds() {
        Expression<String> extract = mock(Expression.class);
        when(cb.function(eq("jsonb_extract_path_text"), eq(String.class), any(), any())).thenReturn(extract);
        when(cb.equal(any(Expression.class), any())).thenReturn(mock(Predicate.class));
        Predicate andPred = mock(Predicate.class);
        when(cb.and(any(Predicate[].class))).thenReturn(andPred);
        Predicate orPred = mock(Predicate.class);
        when(cb.or(any(Predicate[].class))).thenReturn(orPred);

        PartialResult residual = new PartialResult(
                PartialResult.Decision.CONDITIONAL,
                List.of(
                        new Conjunction(List.of(new Condition("tags.region", Condition.Operator.EQ, "emea"))),
                        new Conjunction(List.of(new Condition("tags.sensitivity", Condition.Operator.EQ, "public")))));

        Predicate result = factory.from(residual).toPredicate(root, query, cb);

        assertThat(result).isSameAs(orPred);
        // two AND-groups combined by one OR
        verify(cb, org.mockito.Mockito.times(2)).and(any(Predicate[].class));
        verify(cb).or(any(Predicate[].class));
    }

    private static PartialResult conditional(Condition condition) {
        return new PartialResult(
                PartialResult.Decision.CONDITIONAL, List.of(new Conjunction(List.of(condition))));
    }
}
