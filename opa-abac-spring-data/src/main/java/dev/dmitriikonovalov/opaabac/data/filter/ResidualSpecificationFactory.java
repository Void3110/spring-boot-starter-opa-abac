package dev.dmitriikonovalov.opaabac.data.filter;

import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.data.model.Taggable;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Translates a {@link PartialResult} residual (the DNF compiled by OPA partial evaluation) into a Spring
 * Data JPA {@link Specification} over the entity's {@code tags} JSONB column and intrinsic columns.
 *
 * <p>The mapping mirrors the three residual outcomes:
 * <ul>
 *   <li>{@link PartialResult.Decision#ALLOW_ALL} → no predicate ({@code Specification.where(null)}); the
 *       caller's own scope filter still applies (the residual is <strong>AND-ed with</strong> it, never a
 *       replacement);</li>
 *   <li>{@link PartialResult.Decision#DENY_ALL} → an always-false predicate ({@code cb.disjunction()}),
 *       so the query matches no row — the fail-closed shape;</li>
 *   <li>{@link PartialResult.Decision#CONDITIONAL} → {@code OR( AND(conditions) )} over the DNF.</li>
 * </ul>
 *
 * <h2>Per-condition translation (Postgres dialect via the {@link JsonPathDialect} seam)</h2>
 * <ul>
 *   <li>{@code tags.<k> EQ/NEQ <v>} → {@code jsonb_extract_path_text(tags,'<k>')} compared to {@code <v>};</li>
 *   <li>{@code tags.<k> IN [<v>…]} → {@code jsonb_extract_path_text(...)} {@code IN (…)};</li>
 *   <li>{@code tags.<k> CONTAINS <v>} (array tag) → the {@code ?} existence op
 *       ({@code jsonb_exists(tags->'<k>','<v>')}) — the scalar-vs-array split mirrors the
 *       {@code resource_tag_values} normalize in the tag-grant Rego;</li>
 *   <li>an intrinsic path (no {@code tags.} prefix, e.g. {@code categoryId}) → {@code root.get("<field>")}.</li>
 * </ul>
 *
 * <p>All literals are <strong>bound</strong> via the criteria builder (no SQL string interpolation), so
 * the predicate is injection-safe and dialect-portable. The translator is stateless and reusable.
 */
public final class ResidualSpecificationFactory {

    private final JsonPathDialect dialect;

    /** Default factory (Postgres dialect). */
    public ResidualSpecificationFactory() {
        this(new JsonPathDialect.Postgres());
    }

    ResidualSpecificationFactory(JsonPathDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Build a {@link Specification} from a compiled residual.
     *
     * @param residual the partial-eval result (never null in practice; a null is treated as deny-all)
     * @param <T>      the entity type
     * @return a specification that, AND-ed with the caller's scope, selects exactly the authorized rows
     */
    public <T> Specification<T> from(PartialResult residual) {
        if (residual == null || residual.decision() == PartialResult.Decision.DENY_ALL) {
            // Always-false predicate — match no row. The fail-closed shape.
            return (root, query, cb) -> cb.disjunction();
        }
        if (residual.decision() == PartialResult.Decision.ALLOW_ALL) {
            // No predicate — the caller's scope filter is the only constraint.
            return Specification.where(null);
        }
        // CONDITIONAL — OR of conjunctions (DNF).
        List<Conjunction> clauses = residual.clauses();
        return (root, query, cb) -> {
            Path<?> tagsPath = root.get(Taggable.TAGS_ATTRIBUTE);
            List<Predicate> disjuncts = new ArrayList<>(clauses.size());
            for (Conjunction conjunction : clauses) {
                if (conjunction.isEmpty()) {
                    // A vacuously-true disjunct makes the whole OR true → contribute a TRUE predicate.
                    return cb.conjunction();
                }
                List<Predicate> conjuncts = new ArrayList<>(conjunction.conditions().size());
                for (Condition condition : conjunction.conditions()) {
                    conjuncts.add(toPredicate(condition, root, tagsPath, cb));
                }
                disjuncts.add(cb.and(conjuncts.toArray(Predicate[]::new)));
            }
            return cb.or(disjuncts.toArray(Predicate[]::new));
        };
    }

    private Predicate toPredicate(Condition condition, Path<?> root, Path<?> tagsPath, CriteriaBuilder cb) {
        if (condition.isTagPath()) {
            return tagPredicate(condition, tagsPath, cb);
        }
        return intrinsicPredicate(condition, root, cb);
    }

    private Predicate tagPredicate(Condition condition, Path<?> tagsPath, CriteriaBuilder cb) {
        String key = condition.tagKey();
        return switch (condition.operator()) {
            case EQ -> cb.equal(dialect.extractText(cb, tagsPath, key), asText(condition.value()));
            case NEQ -> cb.notEqual(dialect.extractText(cb, tagsPath, key), asText(condition.value()));
            case IN -> dialect.extractText(cb, tagsPath, key).in(asTextList(condition.value()));
            case CONTAINS -> cb.isTrue(dialect.arrayContains(cb, tagsPath, key, asText(condition.value())));
        };
    }

    private Predicate intrinsicPredicate(Condition condition, Path<?> root, CriteriaBuilder cb) {
        Path<Object> column = root.get(condition.path());
        return switch (condition.operator()) {
            case EQ -> cb.equal(column, condition.value());
            case NEQ -> cb.notEqual(column, condition.value());
            case IN -> column.in(asList(condition.value()));
            // CONTAINS has no meaning on a scalar intrinsic column — fail closed for that row.
            case CONTAINS -> cb.disjunction();
        };
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static List<String> asTextList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                out.add(asText(element));
            }
        } else {
            out.add(asText(value));
        }
        return out;
    }

    private static List<Object> asList(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of(value);
    }
}
