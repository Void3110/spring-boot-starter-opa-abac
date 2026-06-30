package dev.dmitriikonovalov.opaabac.data.filter;

import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Resolves the set of resource ids a subject <strong>governs</strong> through team membership — the rows
 * whose visibility is a <em>membership</em> question the policy's partial-evaluation residual cannot decide
 * on its own (Slice B4, ADR 0018) — and exposes that set both as raw ids and as a ready-to-compose
 * {@link Specification} base scope.
 *
 * <h2>Why a separate seam (not OPA partial-eval)</h2>
 * Catalog visibility is a join — "which team governs this catalog, and am I a member?" — that lives in the
 * user-service, keyed by the exact resource id, <strong>not</strong> an attribute on the resource row. OPA
 * partial-evaluation filters on {@code input.resource.*} columns/tags, so it has nothing to bite on for a
 * pure membership relation. This SPI resolves the membership set app-side and hands back both the governed
 * ids (so the caller can resolve the subject's role on one of them to drive the {@code filter} residual)
 * and the {@code id IN (governed ids)} base scope; the {@code AbacQueryService} composes the scope as the
 * list's <strong>base {@code scope}</strong> — the AND-gate nothing escapes — with OPA's {@code filter}
 * residual AND-ed on top (the role's {@code list} grant). See {@link AbacQueryService#findAuthorized}.
 *
 * <h2>Fail-closed (the load-bearing invariant — ADR 0018 §5)</h2>
 * Every breach — no governed rows, an empty result, a transport/parse failure talking to the membership
 * source, a {@code null} subject — resolves to an <strong>empty id list</strong> and thus an
 * <strong>always-false</strong> {@link Specification} (an empty page), <strong>never</strong> a throw and
 * <strong>never</strong> the whole table. A missing governed scope is indistinguishable from "governs
 * nothing": both yield the empty page. It follows that an implementation MUST NOT let an exception escape —
 * a transport outage collapses to an empty list, exactly as a list with no governed rows would.
 *
 * @see AbacQueryService#findAuthorized
 */
public interface GovernedScopeResolver {

    /**
     * The ids of {@code resourceType} rows {@code subject} governs through team membership — distinct,
     * possibly empty, <strong>never</strong> {@code null} and <strong>never</strong> throwing. An empty
     * list is the fail-closed value for "governs nothing" <em>and</em> for any membership-source breach
     * (the two are indistinguishable, by design).
     *
     * @param subject      the requesting subject's id (the IdP {@code sub}); governance is a pure
     *     membership question, so this is subject-keyed, not role-keyed. A {@code null}/blank subject →
     *     an empty list.
     * @param resourceType the ABAC type whose governed ids are wanted (e.g. {@code "catalog"})
     * @return the distinct governed ids; empty on "governs nothing" or any breach
     */
    List<UUID> governedIds(String subject, String resourceType);

    /**
     * The base scope for a list of {@code resourceType} rows governed by {@code subject}: an
     * {@code id IN (governed ids)} {@link Specification}, or an <strong>always-false</strong> one when the
     * subject governs nothing or the membership source cannot be reached (fail-closed). A thin convenience
     * over {@link #governedIds} so callers that only need the scope don't rebuild the {@code IN} predicate.
     *
     * @param subject      the requesting subject's id (the IdP {@code sub})
     * @param resourceType the ABAC type whose governed rows are wanted
     * @param <T>          the queried entity type
     * @return the governed-id {@link Specification}; never {@code null}, never throwing
     */
    default <T extends AbacDataObject> Specification<T> governedScope(String subject, String resourceType) {
        List<UUID> ids = governedIds(subject, resourceType);
        if (ids.isEmpty()) {
            return denyAll();
        }
        return (root, query, cb) -> root.get("id").in(ids);
    }

    /**
     * The fail-closed value: an <strong>always-false</strong> {@link Specification} selecting no rows.
     * Shared so every implementation lands on the <em>same</em> empty-list floor (an unsatisfiable
     * predicate, expressed as an empty disjunction) rather than re-deriving it — and so a future reader
     * sees the single fail-closed sink. {@code null}-safe and stateless.
     *
     * @param <T> the queried entity type
     * @return a {@link Specification} whose predicate is always false
     */
    static <T extends AbacDataObject> Specification<T> denyAll() {
        return (root, query, cb) -> cb.disjunction(); // an empty OR → false → selects no rows
    }
}
