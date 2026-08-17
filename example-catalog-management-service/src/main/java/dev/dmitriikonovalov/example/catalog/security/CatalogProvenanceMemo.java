package dev.dmitriikonovalov.example.catalog.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Carries the catalog list's <em>supervised id set</em> from {@code CatalogListAuthorizer} (which
 * knows the leg, because it composed the query from it) to {@code CatalogProvenanceAdvice} (which
 * needs it after the page envelope exists). A request attribute, the repo's existing idiom — the
 * same {@link RequestContextHolder} + {@link RequestAttributes#SCOPE_REQUEST} mechanism as
 * {@code RequestAttributesResourceCache} and the role supplier's request memo. No
 * {@code @RequestScope} bean, no scope proxying.
 *
 * <p><strong>Present-but-empty is not absent, and the distinction is the whole point.</strong> The
 * authorizer writes the set <em>including the empty set</em>, unconditionally, immediately before it
 * runs the query. So:
 * <ul>
 *   <li><b>present and non-empty</b> — rows whose id is in the set came by supervision, the rest by
 *       membership;</li>
 *   <li><b>present and empty</b> — this page has no supervised leg at all (a plain member, an
 *       agent-marked call whose supervised leg is skipped, or a supervised-source outage), so every
 *       row is {@code "member"};</li>
 *   <li><b>absent</b> — the response never passed through the two-leg authorizer's query path, so
 *       the server did not compute the label and the advice <strong>omits</strong> it.</li>
 * </ul>
 * Readers must therefore test <em>presence</em> ({@code isPresent()}), never emptiness. Collapsing
 * the second and third cases would label an uncomputed page {@code "member"} — a confident lie about
 * precisely the rows a second factor guards.
 *
 * <p>No request context (async, a non-web caller) degrades cleanly: {@code write} does nothing and
 * {@code read} returns empty. Neither throws.
 */
public final class CatalogProvenanceMemo {

    private static final String KEY = CatalogProvenanceMemo.class.getName() + ":supervisedIds";

    private CatalogProvenanceMemo() {}

    /** Record the supervised id set for this request — including an empty one. */
    public static void write(List<UUID> supervisedIds) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null || supervisedIds == null) {
            return;
        }
        request.setAttribute(KEY, Set.copyOf(supervisedIds), RequestAttributes.SCOPE_REQUEST);
    }

    /**
     * The supervised id set for this request, or {@link Optional#empty()} when none was recorded.
     * An empty <em>set</em> inside a present Optional is a real answer: "no supervised rows here".
     */
    @SuppressWarnings("unchecked")
    public static Optional<Set<UUID>> read() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return Optional.empty();
        }
        Object value = request.getAttribute(KEY, RequestAttributes.SCOPE_REQUEST);
        return value instanceof Set<?> set ? Optional.of((Set<UUID>) set) : Optional.empty();
    }
}
