package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;

/**
 * Decides whether a list should be <strong>widened by an inheritable ancestor grant</strong>, and if so
 * produces the {@code subtreeSpec} the {@code AbacQueryService} OR-s into the query — the
 * <strong>root-only</strong> resolution at the heart of the hierarchy-aware list filter (Slice 5.5-B).
 *
 * <h2>What it does (root-only — ADR 0010 §1)</h2>
 * A list has no leaf to walk <em>up</em> from, so the resolution inverts: given the subject + the list's
 * <strong>governing root</strong> (e.g. the {@code (catalog, catalogId)} a category list scopes to), it
 * <ol>
 *   <li>resolves the subject's role <strong>once on that root</strong> via {@link RoleDefinitionSupplier} —
 *       exactly as {@link HierarchicalAuthorizer} and the example list authorizer already do;</li>
 *   <li>applies the <strong>inheritable-relation gate</strong>: the queried child type must declare the
 *       governing root's type inheritable (the structural {@code childType -> [ancestorType…]} declaration,
 *       <em>opt-in, default-off</em>) <strong>and</strong> the root-resolved role must grant {@code verb} on
 *       the governing-root type;</li>
 *   <li>on grant → {@code Optional.of(ancestorResolver.subtreeOf(root.type, root.id))} (the whole
 *       governing-root subtree); otherwise → {@code Optional.empty()} (no widening — the tag-only result).</li>
 * </ol>
 * This mirrors the Rego {@code inherited_grant} clause — {@code data.<type>.inheritable[leaf][ancestor]} AND
 * {@code verb in role.permissions[ancestor.type]} — so the widened list and a single-GET decide the
 * <strong>same rows by construction</strong>.
 *
 * <h2>Root-only, not per-node</h2>
 * The only candidate subtree-root is the governing root. There is <strong>no</strong> per-node / mid-tree
 * grant search (a grant on an intermediate category widening only its sub-subtree) — that is Phase 8 (ReBAC).
 *
 * <h2>Fail-closed (ADR 0010 §1; the load-bearing invariant)</h2>
 * No role definition resolved, the role not granting the verb on the root type, the relation not declared
 * inheritable, or <strong>any resolution exception</strong> → {@link Optional#empty()}. The result then falls
 * back to the <strong>narrower</strong> tag-only filter, never wider. ({@link AncestorResolver#subtreeOf}
 * itself is fail-closed to an empty predicate, so even a non-empty {@code Optional} never over-widens.)
 *
 * <p>A role-source <strong>outage</strong> ({@link RoleResolutionException}, B2) is one such resolution
 * exception: the role lookup sits inside the {@code catch (RuntimeException)} below, so an outage collapses
 * to <strong>no widening</strong> (the narrower tag-only filter) — the same fail-closed posture, by the
 * same catch. No code change was needed for B2; this is pinned by a test so a refactor cannot silently
 * widen it.
 */
public class SubtreeSpecResolver {

    private final AncestorResolver ancestorResolver;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final Map<String, List<String>> inheritable;

    /**
     * @param ancestorResolver       the SPI producing the {@code subtreeOf} predicate (5.5-B T1)
     * @param roleDefinitionSupplier the role lookup (resolved once on the governing root)
     * @param inheritable            the structural inheritance declaration {@code childType ->
     *     [ancestorType…]} (opt-in, default-off) — the same map the starter mirrors into OPA data; a
     *     {@code null} or empty map means no relation is inheritable, so the resolver never widens
     */
    public SubtreeSpecResolver(
            AncestorResolver ancestorResolver,
            RoleDefinitionSupplier roleDefinitionSupplier,
            Map<String, List<String>> inheritable) {
        this.ancestorResolver = Objects.requireNonNull(ancestorResolver, "ancestorResolver");
        this.roleDefinitionSupplier =
                Objects.requireNonNull(roleDefinitionSupplier, "roleDefinitionSupplier");
        this.inheritable = inheritable == null ? Map.of() : Map.copyOf(inheritable);
    }

    /**
     * The {@code subtreeSpec} widening for a list of {@code childType} scoped to {@code governingRoot}, when
     * the subject's root-resolved role inheritably grants {@code verb}; otherwise empty.
     *
     * @param subject       the requesting subject (its id resolves the role); a {@code null} subject → empty
     * @param childType     the ABAC type of the rows being listed (e.g. {@code "category"}) — the type whose
     *     inheritance from the governing root is gated
     * @param governingRoot the root the list scopes to (e.g. {@code (catalog, catalogId)})
     * @param verb          the permission token the root-resolved role must carry for the governing
     *     root's type. The check is RAW token membership — under coarse-category roles (Phase 6.5)
     *     pass the category token (e.g. {@code READ}), and the caller is responsible for pre-gating
     *     anything token membership cannot see (deny-overrides, tag requirements) before widening —
     *     see the catalog example's {@code CategoryListAuthorizer}
     * @param <T>           the queried entity type
     * @return {@code Optional.of(subtreeSpec)} when the inheritable gate passes; {@link Optional#empty()}
     *     otherwise (fail-closed) — never {@code null}
     */
    public <T extends AbacDataObject> Optional<Specification<T>> subtreeSpec(
            AbacContext.Subject subject, String childType, ParentRef governingRoot, String verb) {
        Objects.requireNonNull(childType, "childType");
        Objects.requireNonNull(governingRoot, "governingRoot");
        Objects.requireNonNull(verb, "verb");
        if (subject == null) {
            return Optional.empty(); // fail-closed: no subject
        }

        try {
            // Gate 1 — the relation must be declared inheritable (opt-in, default-off): the queried child
            // type lists the governing root's type as an inheritable ancestor.
            if (!isInheritable(childType, governingRoot.type())) {
                return Optional.empty();
            }

            // Resolve the role ONCE on the governing root (never per-ancestor — root-only).
            RoleDefinition roleDefinition = roleDefinitionSupplier
                    .lookup(subject.id(), governingRoot.type(), governingRoot.id())
                    .orElse(null);
            if (roleDefinition == null) {
                return Optional.empty(); // fail-closed: unresolved role
            }

            // Gate 2 — the root-resolved role must grant the verb on the governing-root type.
            if (!grantsVerb(roleDefinition, governingRoot.type(), verb)) {
                return Optional.empty();
            }

            // Granted → widen by the whole governing-root subtree. (subtreeOf is itself fail-closed.)
            return Optional.of(ancestorResolver.subtreeOf(governingRoot.type(), governingRoot.id()));
        } catch (RuntimeException e) {
            // Any resolution failure → no widening (never wider). subtreeOf already swallows; this guards the
            // role lookup / inheritance read. B2: a role-source outage (RoleResolutionException) is one such
            // failure and collapses here to no widening — fail-closed, by this same catch (pinned by a test).
            return Optional.empty();
        }
    }

    /** The child type declares {@code ancestorType} as an inheritable ancestor (opt-in, default-off). */
    private boolean isInheritable(String childType, String ancestorType) {
        List<String> ancestors = inheritable.get(childType);
        return ancestors != null && ancestors.contains(ancestorType);
    }

    /** Raw token membership: the role's permission list for {@code resourceType} contains {@code verb}. */
    private static boolean grantsVerb(RoleDefinition roleDefinition, String resourceType, String verb) {
        List<String> verbs = roleDefinition.permissions().get(resourceType);
        return verbs != null && verbs.contains(verb);
    }
}
