package dev.dmitriikonovalov.opaabac.data.filter;

import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import org.springframework.data.jpa.domain.Specification;

/**
 * Supplies the <strong>governed base scope</strong> for a resource list: a {@link Specification} selecting
 * exactly the rows of {@code resourceType} that {@code subject} governs through team membership — the rows
 * whose visibility is a <em>membership</em> question the policy's partial-evaluation residual cannot decide
 * on its own (Slice B4, ADR 0018).
 *
 * <h2>Why a separate seam (not OPA partial-eval)</h2>
 * Catalog visibility is a join — "which team governs this catalog, and am I a member?" — that lives in the
 * user-service, keyed by the exact resource id, <strong>not</strong> an attribute on the resource row. OPA
 * partial-evaluation filters on {@code input.resource.*} columns/tags, so it has nothing to bite on for a
 * pure membership relation. This SPI resolves the membership set app-side and hands back the
 * {@code id IN (governed ids)} predicate; the {@code AbacQueryService} composes it as the list's
 * <strong>base {@code scope}</strong> — the AND-gate nothing escapes — with OPA's {@code filter} residual
 * AND-ed on top (the role's {@code list} grant). See {@link AbacQueryService#findAuthorized}.
 *
 * <h2>Fail-closed (the load-bearing invariant — ADR 0018 §5)</h2>
 * Every breach — no governed rows, an empty result, a transport/parse failure talking to the membership
 * source, a {@code null} subject — resolves to an <strong>always-false</strong> {@link Specification} (an
 * empty list), <strong>never</strong> a throw and <strong>never</strong> the whole table. A missing
 * governed scope is indistinguishable from "governs nothing": both yield the empty page. This mirrors the
 * fail-closed posture of {@code AncestorResolver.subtreeOf} / {@code SubtreeSpecResolver} (an empty/absent
 * widening, never wider) — here applied to the base scope, so the floor is empty rather than wide.
 *
 * <p>It follows that an implementation MUST NOT let an exception escape: a transport outage to the
 * membership service collapses to {@link #denyAll()} (an empty list), exactly as a list with no governed
 * rows would — the same fail-closed page, never a 500 and never a leak.
 *
 * @see AbacQueryService#findAuthorized
 */
public interface GovernedScopeResolver {

    /**
     * The base scope for a list of {@code resourceType} rows governed by {@code subject}: an
     * {@code id IN (governed ids)} {@link Specification}, or an <strong>always-false</strong> one when the
     * subject governs nothing or the membership source cannot be reached (fail-closed).
     *
     * @param subject      the requesting subject's id (the IdP {@code sub}); governance is a pure
     *     membership question, so this is subject-keyed, not role-keyed. A {@code null}/blank subject →
     *     {@link #denyAll()}.
     * @param resourceType the ABAC type whose governed rows are wanted (e.g. {@code "catalog"})
     * @param <T>          the queried entity type
     * @return the governed-id {@link Specification}; never {@code null}, never throwing — an always-false
     *     Specification on any breach
     */
    <T extends AbacDataObject> Specification<T> governedScope(String subject, String resourceType);

    /**
     * The fail-closed value: an <strong>always-false</strong> {@link Specification} selecting no rows.
     * Shared so every implementation lands on the <em>same</em> empty-list floor (an unsatisfiable
     * predicate, expressed as {@code 1 = 0}) rather than re-deriving it — and so a future reader sees the
     * single fail-closed sink. {@code null}-safe and stateless.
     *
     * @param <T> the queried entity type
     * @return a {@link Specification} whose predicate is always false
     */
    static <T extends AbacDataObject> Specification<T> denyAll() {
        return (root, query, cb) -> cb.disjunction(); // an empty OR → false → selects no rows
    }
}
