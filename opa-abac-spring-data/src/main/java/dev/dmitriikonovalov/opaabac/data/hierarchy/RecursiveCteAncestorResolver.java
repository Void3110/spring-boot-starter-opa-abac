package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/**
 * The alternative {@link AncestorResolver}: walks the <strong>live parent linkage</strong> up to the root
 * via a {@link ParentLinkSource} (typically backed by a recursive CTE / per-hop query over {@code parent_id}),
 * carrying <strong>no denormalized path column</strong>. Because it reads live adjacency, it is
 * <em>correct-by-construction on a re-parent</em> — there is no materialized state to rewrite, so a moved
 * subtree resolves to its new lineage immediately.
 *
 * <p>The walk composes one hop at a time, building the chain from the leaf's parent up to the root, then
 * reverses it to the contract's root-first order (the leaf itself is never added, satisfying leaf-exclusion).
 *
 * <h2>Fail-closed</h2>
 * <ul>
 *   <li><b>Cycle detection</b> via a visited-set keyed on {@code type:id}: revisiting a node throws
 *       (never an infinite loop).</li>
 *   <li><b>Depth bound</b>: more than {@code maxDepth} hops throws (never a truncated chain).</li>
 *   <li>Any error from the source propagates wrapped as {@link AncestorResolutionException}.</li>
 * </ul>
 * A clean walk that reaches a parent-less root returns the assembled chain; an empty chain means the leaf
 * is itself a root.
 */
public class RecursiveCteAncestorResolver implements AncestorResolver {

    private final ParentLinkSource parentSource;
    private final DescendantIdSource descendantSource;
    private final int maxDepth;

    /**
     * @param parentSource the per-app one-hop parent lookup (the live adjacency relation)
     * @param maxDepth     the maximum number of hops the walk may take; a deeper chain throws (mandatory)
     */
    public RecursiveCteAncestorResolver(ParentLinkSource parentSource, int maxDepth) {
        this(parentSource, null, maxDepth);
    }

    /**
     * @param parentSource     the per-app one-hop parent lookup (the live adjacency relation, walked up)
     * @param descendantSource the per-app one-hop child lookup (the live adjacency relation, walked down) for
     *     {@link #subtreeOf}; may be {@code null}, in which case {@code subtreeOf} fails closed (the empty
     *     predicate — a list does not widen via the CTE path), which is safe
     * @param maxDepth         the maximum number of hops a walk may take; a deeper chain throws (mandatory)
     */
    public RecursiveCteAncestorResolver(
            ParentLinkSource parentSource, DescendantIdSource descendantSource, int maxDepth) {
        this.parentSource = Objects.requireNonNull(parentSource, "parentSource");
        this.descendantSource = descendantSource;
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, was " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    @Override
    public List<ParentRef> ancestorsOf(String leafType, String leafId) {
        Objects.requireNonNull(leafType, "leafType");
        Objects.requireNonNull(leafId, "leafId");

        // Built leaf→root, then reversed to root-first. The leaf is never added (leaf-exclusion).
        Deque<ParentRef> chainLeafToRoot = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        visited.add(key(leafType, leafId));

        String currentType = leafType;
        String currentId = leafId;
        int hops = 0;

        while (true) {
            Optional<ParentRef> parent;
            try {
                parent = parentSource.parentOf(currentType, currentId);
            } catch (RuntimeException e) {
                throw new AncestorResolutionException(
                        "parent-link lookup failed for " + currentType + ":" + currentId, e);
            }
            if (parent.isEmpty()) {
                break; // reached a root — clean end of walk
            }

            ParentRef p = parent.get();
            hops++;
            if (hops > maxDepth) {
                throw new AncestorResolutionException(
                        "ancestor walk exceeded maxDepth " + maxDepth + " from " + leafType + ":" + leafId);
            }
            if (!visited.add(key(p.type(), p.id()))) {
                throw new AncestorResolutionException(
                        "cycle detected at " + p.type() + ":" + p.id()
                                + " while resolving " + leafType + ":" + leafId);
            }

            chainLeafToRoot.push(p); // push so iteration yields root-first
            currentType = p.type();
            currentId = p.id();
        }

        return List.copyOf(chainLeafToRoot); // ArrayDeque iteration order == push order == root-first
    }

    /**
     * The subtree predicate as a bounded {@code id IN (…)} over the descendant id set, materialized by a
     * <strong>downward {@code parent_id} walk</strong> via the {@link DescendantIdSource}. The root id is
     * included, then a breadth-first descent collects every descendant id, <strong>bounded by
     * {@code maxDepth} levels</strong> and cycle-guarded by a visited set.
     *
     * <h2>Fail-closed</h2>
     * No {@link DescendantIdSource} wired, a depth breach, a detected cycle, or any source/SQL error collapses
     * to an <strong>always-false</strong> predicate — the descendant id set is discarded and the list falls
     * back to the narrower tag-only result, <em>never</em> an unbounded {@code IN} and never the whole table.
     *
     * <p>The returned predicate compares the queried entity's {@code id} column to the bounded id set, so a
     * list scoped to one child type matches only that type's rows in the subtree (the ids that belong to the
     * other types simply do not appear in that table).
     */
    @Override
    public <T> Specification<T> subtreeOf(String rootType, String rootId) {
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(rootId, "rootId");
        if (descendantSource == null) {
            return alwaysFalse(); // no downward source → no widening (safe)
        }

        List<String> descendantIds;
        try {
            descendantIds = collectSubtreeIds(rootType, rootId);
        } catch (AncestorResolutionException _) {
            // A depth breach / cycle / SQL error must fail closed (empty widening), never the whole table.
            return alwaysFalse();
        }
        if (descendantIds.isEmpty()) {
            return alwaysFalse();
        }

        // Bind the ids as the same type as the entity's id column. The base entities use UUID ids, so a
        // string id that parses as a UUID is bound as a UUID (a bare String would fail "uuid = varchar" in
        // Postgres); a non-UUID id is bound verbatim. The criteria `in(...)` keeps the literals bound.
        final List<Object> boundIds = new ArrayList<>(descendantIds.size());
        for (String id : descendantIds) {
            boundIds.add(asIdValue(id));
        }
        return (root, query, cb) -> root.get("id").in(boundIds);
    }

    /** A UUID-shaped id becomes a {@link java.util.UUID} (to match a {@code uuid} column); else verbatim. */
    private static Object asIdValue(String id) {
        try {
            return java.util.UUID.fromString(id);
        } catch (IllegalArgumentException _) {
            return id;
        }
    }

    /**
     * Breadth-first descent from {@code (rootType, rootId)} collecting the root id and all descendant ids,
     * bounded by {@code maxDepth} levels and cycle-guarded. Throws {@link AncestorResolutionException} on a
     * breach so {@link #subtreeOf} can collapse fail-closed.
     */
    private List<String> collectSubtreeIds(String rootType, String rootId) {
        List<String> ids = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<ParentRef> frontier = new ArrayDeque<>();

        ParentRef rootRef = new ParentRef(rootType, rootId);
        visited.add(key(rootType, rootId));
        ids.add(rootId);
        frontier.add(rootRef);

        int level = 0;
        while (!frontier.isEmpty()) {
            level++;
            if (level > maxDepth) {
                throw new AncestorResolutionException(
                        "subtree walk exceeded maxDepth " + maxDepth + " from " + rootType + ":" + rootId);
            }
            int levelSize = frontier.size();
            for (int i = 0; i < levelSize; i++) {
                ParentRef node = frontier.poll();
                List<ParentRef> children;
                try {
                    children = descendantSource.childrenOf(node.type(), node.id());
                } catch (RuntimeException e) {
                    throw new AncestorResolutionException(
                            "child-link lookup failed for " + node.type() + ":" + node.id(), e);
                }
                for (ParentRef child : children) {
                    if (!visited.add(key(child.type(), child.id()))) {
                        throw new AncestorResolutionException(
                                "cycle detected at " + child.type() + ":" + child.id()
                                        + " while resolving subtree of " + rootType + ":" + rootId);
                    }
                    ids.add(child.id());
                    frontier.add(child);
                }
            }
        }
        return ids;
    }

    /** An always-false predicate — the fail-closed empty-widening shape (matches no row). */
    private static <T> Specification<T> alwaysFalse() {
        return (root, query, cb) -> cb.disjunction();
    }

    private static String key(String type, String id) {
        return type + ":" + id;
    }

    /** A convenience factory matching the SPI source shape (ancestor walk only). */
    public static RecursiveCteAncestorResolver of(ParentLinkSource parentSource, int maxDepth) {
        return new RecursiveCteAncestorResolver(parentSource, maxDepth);
    }

    /** A convenience factory wiring both the up- and down-walk sources (enables {@link #subtreeOf}). */
    public static RecursiveCteAncestorResolver of(
            ParentLinkSource parentSource, DescendantIdSource descendantSource, int maxDepth) {
        return new RecursiveCteAncestorResolver(parentSource, descendantSource, maxDepth);
    }

    /** Exposed for the starter/tests to confirm the configured bound. */
    public int maxDepth() {
        return maxDepth;
    }
}
