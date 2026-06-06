package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    private final int maxDepth;

    /**
     * @param parentSource the per-app one-hop parent lookup (the live adjacency relation)
     * @param maxDepth     the maximum number of hops the walk may take; a deeper chain throws (mandatory)
     */
    public RecursiveCteAncestorResolver(ParentLinkSource parentSource, int maxDepth) {
        this.parentSource = Objects.requireNonNull(parentSource, "parentSource");
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

    private static String key(String type, String id) {
        return type + ":" + id;
    }

    /** A convenience factory matching the SPI source shape. */
    public static RecursiveCteAncestorResolver of(ParentLinkSource parentSource, int maxDepth) {
        return new RecursiveCteAncestorResolver(parentSource, maxDepth);
    }

    /** Exposed for the starter/tests to confirm the configured bound. */
    public int maxDepth() {
        return maxDepth;
    }
}
