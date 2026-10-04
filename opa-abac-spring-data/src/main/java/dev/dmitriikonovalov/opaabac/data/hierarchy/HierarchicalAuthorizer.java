package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single-resource hierarchical authorization seam: it ties the {@link AncestorResolver} → the
 * {@code input.resource.ancestors} chain → OPA into one <strong>fail-closed</strong> decision. This is the
 * single-GET analogue of how {@code AbacQueryService} ties the list path; a controller stays thin: load the
 * leaf, call {@link #isAllowed}, return 200/403.
 *
 * <h2>What it does, per the design</h2>
 * <ol>
 *   <li>Resolve the leaf's ancestor chain (root-first, leaf-excluded) via the {@link AncestorResolver}.</li>
 *   <li>Resolve the role <strong>once on the governing root</strong> — the chain's first element, or the
 *       leaf itself when there is no inheritable lineage (exactly today's one-step behavior generalized).</li>
 *   <li>Build the {@link AbacContext} with the leaf's tags as {@code resource.attributes} <em>and</em> the
 *       chain as {@code resource.ancestors}, then call {@link OpaClient#allow}.</li>
 * </ol>
 *
 * <h2>Fail-closed (the load-bearing invariant)</h2>
 * The policy decides {@code final_allow = direct_leaf_grant OR (walk_ok AND inherited_grant)}. This seam
 * guarantees the {@code walk_ok} half: an {@link AncestorResolutionException} (cycle / broken / too-deep /
 * SQL / null-path) collapses the chain to <strong>empty</strong>, so {@code inherited_grant} is unreachable
 * and the decision can only come from the <em>direct</em> leaf grant — degrading to the pre-hierarchy
 * decision, never wider, never stripping a direct grant. An unresolved role / no subject → deny. The role
 * is resolved <strong>once on the root</strong>, never per-ancestor (per-node grants are Phase 8 / ReBAC).
 *
 * <p><strong>"Could not decide" is not a deny (ADR 0037).</strong> A role-source outage or a policy-engine
 * failure throws a {@link DecisionIndeterminateException} out of {@link #isAllowed} instead of answering
 * {@code false}, so the caller can answer "not now" rather than "no". It is still fail-closed — the call
 * never proceeds — and an ancestor resolver's own outage subtype passes through the chain-collapse catch.
 *
 * <h2>Tier-unaware (ADR 0032)</h2>
 * This seam never populates {@code input.resource.root_attributes} — root-attribute enrichment is pinned
 * to the annotation gate's authorization manager (ADR 0032 §Population), and this class has no resolver
 * seam to load the root's tags through ({@link ParentRef} carries type and id only). Consequence, in the
 * fail-closed direction: a role stamped {@code provenance == "supervised"} hits the unproven-tier deny
 * (absent {@code root_attributes}, ADR 0030 §3) on every child read through this seam, even on a
 * non-production root. The supervised path's supported seam is {@code @OpaPreAuthorize}; an adopter
 * routing supervised traffic here gets a spurious deny, never a widening.
 */
public class HierarchicalAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalAuthorizer.class);

    private final AncestorResolver ancestorResolver;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final OpaClient opaClient;

    public HierarchicalAuthorizer(
            AncestorResolver ancestorResolver,
            RoleDefinitionSupplier roleDefinitionSupplier,
            OpaClient opaClient) {
        this.ancestorResolver = Objects.requireNonNull(ancestorResolver, "ancestorResolver");
        this.roleDefinitionSupplier = Objects.requireNonNull(roleDefinitionSupplier, "roleDefinitionSupplier");
        this.opaClient = Objects.requireNonNull(opaClient, "opaClient");
    }

    /**
     * Decide {@code <leaf.type>:<verb>} on a loaded leaf for the given subject, considering the whole
     * ancestor chain. Fail-closed throughout.
     *
     * @param subject the requesting subject (its id resolves the role); a {@code null} subject denies
     * @param verb    the action verb (e.g. {@code "read"}); combined with the leaf type into the action
     * @param leaf    the loaded resource being accessed (supplies type, id, tags, and its parent hop)
     * @return {@code true} iff the policy allows; {@code false} for a deny
     * @throws DecisionIndeterminateException when no decision could be made — the role source was down
     *     ({@link RoleResolutionException}) or the policy engine failed (ADR 0037). Fail-closed all the same:
     *     a throw is never an allow. A caller renders it as "not now" (503), never as a 403.
     */
    public boolean isAllowed(AbacContext.Subject subject, String verb, AbacResource leaf) {
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(leaf, "leaf");
        if (subject == null) {
            return false; // fail-closed: no subject
        }

        String leafType = leaf.abacResourceType();
        String leafId = leaf.abacResourceId();

        // 1) Resolve the ancestor chain — a failed/cyclic/too-deep walk collapses to NO ancestors (never
        //    wider). The resolver throws on any breach; we degrade to the direct-grant-only decision.
        List<ParentRef> ancestors;
        try {
            ancestors = ancestorResolver.ancestorsOf(leafType, leafId);
        } catch (AncestorResolutionException _) {
            ancestors = List.of();
        }

        // 2) Resolve the role ONCE on the governing root: the chain's first element, or the leaf itself when
        //    there is no inheritable lineage. A role-source outage (RoleResolutionException) PROPAGATES: the
        //    role is unknown, so no decision is possible — not a deny (ADR 0037). A separate failure axis from
        //    AncestorResolutionException above (chain-collapse), and never the realm fallback (ADR 0014).
        ParentRef governingRoot = ancestors.isEmpty()
                ? new ParentRef(leafType, leafId)
                : ancestors.get(0);
        RoleDefinition roleDefinition = roleDefinitionSupplier
                .lookup(subject.id(), governingRoot.type(), governingRoot.id())
                .orElse(null);
        if (roleDefinition == null) {
            return false; // fail-closed: unresolved role
        }

        // 3) Build the context with the leaf's tags AND the ancestor chain, then ask OPA.
        AbacContext.Resource resource =
                new AbacContext.Resource(leafType, leafId, leaf.abacAttributes(), ancestors);
        AbacContext context =
                new AbacContext(subject, leafType + ":" + verb, resource, roleDefinition, Map.of());

        try {
            return opaClient.allow(context);
        } catch (DecisionIndeterminateException e) {
            log.debug("hierarchical authorize indeterminate: {}", e.getClass().getSimpleName());
            throw e; // the policy engine could not decide (ADR 0037) — no decision, not a deny
        } catch (RuntimeException _) {
            return false; // fail-closed: any other OPA-side error denies
        }
    }
}
