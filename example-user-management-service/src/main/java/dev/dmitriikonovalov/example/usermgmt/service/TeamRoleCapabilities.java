package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import java.util.List;
import java.util.Map;

/**
 * The management-capability ladder for a role <em>on its own team</em> — the verbs of resource type
 * {@code "team"} the dogfooded management API authorizes against (see {@code team.rego}). This is the
 * projection the user-service's own {@code RoleDefinitionSupplier} returns when the resource type is
 * {@code "team"}; it is distinct from a role's <em>resource</em> permissions on the team-target type
 * (which the effective-role resolve API returns to the catalog, ticket 7).
 *
 * <ul>
 *   <li>{@code owner}         → read, manage, define-roles, transfer-ownership</li>
 *   <li>{@code administrator} → read, manage</li>
 *   <li>{@code member} / {@code viewer} / custom → read</li>
 * </ul>
 *
 * Custom (team-scoped) roles carry no management capability beyond read — they grant resource
 * permissions on the team-target, not team-administration rights.
 */
public final class TeamRoleCapabilities {

    public static final String MANAGE = "manage";
    public static final String DEFINE_ROLES = "define-roles";
    public static final String TRANSFER_OWNERSHIP = "transfer-ownership";

    private static final Map<String, List<String>> BY_CODE = Map.of(
            SystemRoles.OWNER, List.of("read", MANAGE, DEFINE_ROLES, TRANSFER_OWNERSHIP),
            SystemRoles.ADMINISTRATOR, List.of("read", MANAGE),
            SystemRoles.MEMBER, List.of("read"),
            SystemRoles.VIEWER, List.of("read"));

    private TeamRoleCapabilities() {
    }

    /** The management verbs (on resource type {@code "team"}) the given role code grants. */
    public static List<String> forCode(String roleCode) {
        return BY_CODE.getOrDefault(roleCode, List.of("read"));
    }
}
