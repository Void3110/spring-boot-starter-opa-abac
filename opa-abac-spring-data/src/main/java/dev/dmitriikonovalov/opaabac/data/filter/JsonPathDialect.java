package dev.dmitriikonovalov.opaabac.data.filter;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;

/**
 * The JSONB dialect seam for {@link ResidualSpecificationFactory} — the one place that knows the database
 * function names used to read a tag value out of the {@code tags} JSONB column.
 *
 * <p>This slice ships a single {@link Postgres} implementation; the interface exists so a future
 * non-Postgres dialect is a <em>seam, not a rewrite</em> (it is deliberately not a second dialect here).
 * The translator works only in terms of these two operations, so it carries no SQL strings and no Postgres
 * function names of its own.
 */
interface JsonPathDialect {

    /**
     * A scalar text extraction of {@code tags -> key}, for {@code EQ}/{@code NEQ}/{@code IN} comparisons.
     *
     * @param cb       the criteria builder
     * @param tagsPath the path to the {@code tags} JSONB attribute on the entity root
     * @param key      the tag key
     * @return a {@code String} expression equal to the tag's scalar text value (NULL if absent)
     */
    Expression<String> extractText(CriteriaBuilder cb, Path<?> tagsPath, String key);

    /**
     * An array-membership test of {@code tags -> key} containing {@code value}, for {@code CONTAINS} (the
     * Postgres {@code ?} existence operator).
     *
     * @param cb       the criteria builder
     * @param tagsPath the path to the {@code tags} JSONB attribute on the entity root
     * @param key      the tag key (whose value is a JSONB array)
     * @param value    the element to test for
     * @return a {@code Boolean} expression true when the array tag contains {@code value}
     */
    Expression<Boolean> arrayContains(CriteriaBuilder cb, Path<?> tagsPath, String key, String value);

    /** The Postgres dialect: {@code jsonb_extract_path_text} for scalars, {@code jsonb_exists} for arrays. */
    final class Postgres implements JsonPathDialect {

        @Override
        public Expression<String> extractText(CriteriaBuilder cb, Path<?> tagsPath, String key) {
            // jsonb_extract_path_text(tags, '<key>') — returns the scalar text at the key, or SQL NULL.
            return cb.function("jsonb_extract_path_text", String.class, tagsPath, cb.literal(key));
        }

        @Override
        public Expression<Boolean> arrayContains(CriteriaBuilder cb, Path<?> tagsPath, String key, String value) {
            // jsonb_exists(tags -> '<key>', '<value>') — the `?` existence operator over the array element.
            Expression<?> arrayAtKey =
                    cb.function("jsonb_extract_path", Object.class, tagsPath, cb.literal(key));
            return cb.function("jsonb_exists", Boolean.class, arrayAtKey, cb.literal(value));
        }
    }
}
