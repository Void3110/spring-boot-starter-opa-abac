package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import java.util.List;
import java.util.Map;

/**
 * The management-capability ladder for a role <em>on its own team</em> — the {@code "team"}-type
 * permissions the dogfooded management API authorizes against (see {@code team.rego}). Since Phase 6.7
 * (ADR 0015) this projects each system role <b>code</b> into <b>coarse category tokens</b> (not fine
 * verbs); {@code team.rego} expands those tokens to the fine management verbs through the <em>same</em>
 * shared {@code data.permission_categories} table the catalog uses. This is the projection the
 * user-service's own {@code RoleDefinitionSupplier} returns when the resource type is {@code "team"}; it
 * is distinct from a role's <em>resource</em> permissions on the team-target type (which the
 * effective-role resolve API returns to the catalog, ticket 7).
 *
 * <ul>
 *   <li>{@code owner}         → {@code [READ, CONTROL, TAG]}</li>
 *   <li>{@code administrator} → {@code [READ, CONTROL, TAG]}</li>
 *   <li>{@code senior}        → {@code [READ, CONTROL]} — manages membership but carries no
 *       {@code TAG}, so it cannot {@code define-tags} (the senior's <em>tier/subset</em> constraint
 *       lives in the assignment gates of {@code MembershipService}, an orthogonal axis)</li>
 *   <li>{@code member} / {@code reader} / custom → {@code [READ]} (list-members only)</li>
 * </ul>
 *
 * <p>Effective verbs after expansion: {@code READ → view, list, list-members}; {@code CONTROL →
 * add-member, change-role, remove-member}; {@code TAG → define-tags, assign-tags}. So owner/admin
 * curate tags and manage members, senior manages members but cannot curate the dictionary, and any
 * member/reader can list the roster but mutate nothing.
 *
 * <p>The two escalation-sensitive verbs {@code define-roles} (mint the access ladder) and
 * {@code transfer-ownership} (surrender the team) are <b>not</b> tokens here — they are authorized by an
 * <b>owner-only-by-code fence</b> in {@code team.rego}, keyed on the reserved, unspoofable {@code owner}
 * code. Listing them as tokens would make them category-delegatable, reopening the
 * escalation-via-authoring branch ADR 0007 closed.
 *
 * <p>Custom (team-scoped) roles carry no management capability beyond {@code READ} — a custom level-25
 * role has senior's authoring ceiling but no live assign power (ceiling ≠ capability; the pinned I12
 * cell). They grant resource permissions on the team-target, not team-administration rights;
 * {@code RoleDefinitionService.validateContract} rejects (422) a custom role that tries to carry
 * {@code CONTROL} (or a team-meaningful token) under a {@code "team"} key.
 */
public final class TeamRoleCapabilities {

    /** The four category tokens this ladder grants on {@code type:"team"} (mirroring the OPA table). */
    private static final String READ = "READ";

    private static final String CONTROL = "CONTROL";
    private static final String TAG = "TAG";

    private static final Map<String, List<String>> BY_CODE = Map.of(
            SystemRoles.OWNER, List.of(READ, CONTROL, TAG),
            SystemRoles.ADMINISTRATOR, List.of(READ, CONTROL, TAG),
            SystemRoles.SENIOR, List.of(READ, CONTROL),
            SystemRoles.MEMBER, List.of(READ),
            SystemRoles.READER, List.of(READ));

    private TeamRoleCapabilities() {
    }

    /**
     * The category tokens (on resource type {@code "team"}) the given role code grants. Any code outside
     * the reserved system ladder — every custom role — projects to {@code [READ]}: management-incapable
     * by construction (ceiling ≠ capability).
     */
    public static List<String> forCode(String roleCode) {
        return BY_CODE.getOrDefault(roleCode, List.of(READ));
    }

    /**
     * Whether the given role code is <b>CONTROL-capable</b> — i.e. its rung of this ladder carries the
     * {@code CONTROL} category ({@code owner} / {@code administrator} / {@code senior}). Read as "does
     * this seat own or manage the team", which is the reach rule for the supervised read scope
     * (ADR 0029 §3): a report contributes a team to their manager's supervised set only through a
     * CONTROL-capable seat, so a {@code member} / {@code reader} seat — and every custom role, which
     * projects to {@code [READ]} — does not propagate.
     *
     * <p>Derived from {@link #forCode} rather than from a second list of codes: one ladder, one
     * answer. Add a rung there and this predicate follows automatically.
     */
    public static boolean isControlCapable(String roleCode) {
        return forCode(roleCode).contains(CONTROL);
    }
}
