package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.List;
import java.util.Map;

/**
 * The <b>synthesized</b> supervisor role (ADR 0029 §6) — the read-only role a subject resolves to when
 * they supervise a resource but are a member of no team governing it. Built in code on every request
 * and <b>never stored</b>: there is no row, no migration, and nothing a client can author.
 *
 * <h2>Coarse tokens, never fine verbs</h2>
 * Since ADR 0007/0015 {@code permissions[<type>]} carries the coarse category tokens
 * {@code READ}/{@code WRITE}/{@code TAG}/{@code GRANT}/{@code CONTROL}, which
 * {@code data.permission_categories} expands to fine actions ({@code READ → view, list, list-members}).
 * Writing the fine verbs directly expands to the <b>empty set</b> — the documented fail-closed
 * ∅-expansion — so the role would grant nothing at all and the supervised page would be silently
 * empty. This role therefore grants exactly {@code {<supervised type>: ["READ"]}}.
 *
 * <h2>Contents open by DIRECT grant, never by inheritance (ADR 0030 §1)</h2>
 * For the governing type teams target — {@code catalog} — the role names {@code category} and
 * {@code product} <b>explicitly</b>, so child reads resolve through the ordinary {@code direct_grant}
 * path. That is the whole point of the shape: authority stays in the <em>role</em>, and ADR 0031's
 * confinement rule (ancestor inheritance requires {@code provenance == "membership"}) stays exactly as
 * exact as it was. The role still names no {@code "*"} key, still carries
 * {@link #PROVENANCE_SUPERVISED}, and is still READ-only — so the read-only ceiling is unchanged and
 * inheritance remains closed to it.
 *
 * <p><b>How far that read goes is decided in policy, not here</b> (ADR 0030 §3–4): the leaf policies
 * carry two provenance-scoped {@code denied} clauses that close contents whose governing root is
 * tagged {@code env=production} — or whose tier could not be established at all. Widening the role is
 * therefore not widening access to production detail; it moves the decision to where the tier is
 * visible.
 *
 * <h2>Provenance</h2>
 * Provenance rides the <b>existing</b> generic {@code attributes} map plus the reserved code, so
 * {@code input.role_definition} carries it with <b>zero</b> envelope change — adding a field to
 * {@code core.RoleDefinition} would be exactly the envelope change this slice forbids.
 *
 * <p><b>The code is provenance, not authority.</b> Role codes are only partially unique, so a team
 * owner could define a custom role bearing {@link #SUPERVISOR_CODE}. Per ADR 0029 §7 that is
 * <em>not</em> an escalation and is recorded so it is not re-derived later: reach comes entirely from
 * the org-relation seam and never from the role, so claiming the code grants no additional scope — it
 * only moves the holder onto the stricter branch. Spoofing it is self-demotion.
 */
public final class SupervisorRoles {

    /** The reserved code the synthesized role carries — provenance, never authority (ADR 0029 §7). */
    public static final String SUPERVISOR_CODE = "supervisor-readonly";

    /**
     * The reserved, <b>system-owned</b> attribute key carrying how a role was resolved (ADR 0031 §1).
     * Never client-settable — see {@code RoleDefinitionService}, which strips it on the write path.
     */
    public static final String PROVENANCE_ATTRIBUTE = "provenance";

    /**
     * Provenance of a role resolved from a <b>team membership</b> — the one value that opens ancestor
     * inheritance in {@code category.rego} / {@code product.rego} (ADR 0031 §4). Stamped at the single
     * membership funnel, {@code EffectiveRoleService.resourceRole}.
     */
    public static final String PROVENANCE_MEMBERSHIP = "membership";

    /** Provenance of a role synthesized from the supervised (org-relation) path. */
    public static final String PROVENANCE_SUPERVISED = "supervised";

    /** The single coarse token the supervised path grants — read, and nothing else. */
    private static final List<String> READ_ONLY = List.of("READ");

    /** The governing type teams target; the only one whose contents the tier gates (ADR 0030 §1). */
    private static final String CATALOG_TYPE = "catalog";

    private static final String CATEGORY_TYPE = "category";

    private static final String PRODUCT_TYPE = "product";

    private SupervisorRoles() {
    }

    /**
     * The synthesized read-only role for a supervised resource of {@code resourceType}: the coarse
     * {@code READ} token on <b>that type only</b>, no denials, and <b>vacuous required tags</b>.
     *
     * <p>The vacuous tag requirement is safe <em>only</em> because the two scopes are disjoint
     * ({@code supervised := S \ M}, ADR 0029 §5): no row is reachable by both paths, so this role can
     * never be the one judging a tag-gated membership row. It is also the precondition that makes the
     * list's {@code subtreeSpec} widening correct — an unconditional residual ({@code ALLOW_ALL}) —
     * which the slice asserts explicitly (U34) rather than assuming.
     *
     * @param resourceType the type actually supervised; the role names this type and no other
     */
    public static RoleDefinition readOnlyFor(String resourceType) {
        return new RoleDefinition(
                SUPERVISOR_CODE,
                Map.of(PROVENANCE_ATTRIBUTE, PROVENANCE_SUPERVISED),
                permissionsFor(resourceType));
    }

    /**
     * {@code catalog} — the only governing type teams target today — also grants READ on its two child
     * types, so a supervisor can open contents through the ordinary direct-grant path (ADR 0030 §1).
     * Every other supervised type keeps the single-key shape: naming children there would grant reach
     * over a hierarchy nobody has declared.
     */
    private static Map<String, List<String>> permissionsFor(String resourceType) {
        if (!CATALOG_TYPE.equals(resourceType)) {
            return Map.of(resourceType, READ_ONLY);
        }
        return Map.of(
                CATALOG_TYPE, READ_ONLY,
                CATEGORY_TYPE, READ_ONLY,
                PRODUCT_TYPE, READ_ONLY);
    }
}
