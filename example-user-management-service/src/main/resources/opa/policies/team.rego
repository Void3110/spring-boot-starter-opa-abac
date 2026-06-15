# Team management authorization (the user-management-service dogfooding the starter).
#
# The service secures its OWN management API (membership, role-defs, tag dictionary, transfer) with
# the same @OpaPreAuthorize mechanism it produces role definitions for. The app POSTs
# {"input": <AbacContext>} to /v1/data/team and reads result.allow. Decisions are
# role-definition-driven and CATEGORY-DRIVEN exactly like the catalog's per-type policies (Phase 6.7,
# ADR 0015): the caller's effective management role ON THIS TEAM is resolved server-side and its
# role_definition.permissions["team"] grants COARSE category tokens that EXPAND to the fine
# management verbs through the ONE shared table (data.permission_categories) via the shared
# data.permissions.effective_actions — the same expansion home the catalog uses, refined by
# denied_actions. team.rego is therefore symmetric with catalog.rego: one expansion home, two planes.
#
# Actions all share the "team:" prefix so the verb is a single clean token after the ":".
# The fine verbs and the categories that grant them:
#   READ    -> "view", "list", "list-members"   (list-members: the roster is visibility)
#   CONTROL -> "add-member", "change-role", "remove-member"   (the membership management verbs)
#   TAG     -> "define-tags", "assign-tags"      (define-tags: curate the team's tag dictionary)
# The capability ladder is a Java projection (TeamRoleCapabilities) of the role CODE into these
# tokens; the policy never sees the ladder, only the resolved tokens:
#   owner / administrator -> [READ, CONTROL, TAG]
#   senior                -> [READ, CONTROL]      (no TAG -> no define-tags; bounded by the
#                                                  service-side escalation gates on whom/to-what-tier)
#   member / reader / custom -> [READ]            (list-members only)
#
# TWO escalation-sensitive verbs stay OUTSIDE the category system and are NOT delegatable:
#   "define-roles"        (mint the access ladder)        -> owner only
#   "transfer-ownership"  (surrender the team)            -> owner only
# They are authorized by the owner-only fence below, keyed on the reserved, unspoofable "owner" CODE
# (never on role_level — a custom role can carry any level but never the owner code), so no custom or
# non-owner system role can ever hold them. This preserves ADR 0007's pruning of the
# escalation-via-authoring branch and extends the same fence to transfer-ownership.
#
# The "on whom / to what tier" bounds (cross-tier strict <, the senior subset verdict, the
# target-tier gate, owner-protection) are an ORTHOGONAL axis enforced in MembershipService — NOT
# here. team.rego decides only WHICH KINDS of acts a role may perform (the verb-category axis).
#
# Fail-closed: an unknown/stale/removed token expands to NOTHING (the shared ∅-expansion floor) ->
# deny; a role with no permissions -> deny; default deny.
#
# OPA 1.x: `if`/`in`/`contains` are built-in keywords — no imports needed (other than the shared
# expansion home). Default deny.

package team

import data.permissions

default allow := false

# The action verb is the part after the ":" in input.action
# (e.g. "team:add-member" -> "add-member", "team:transfer-ownership" -> "transfer-ownership").
verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# (1) Category-driven — the delegatable verbs, expanded through the ONE shared table (symmetric with
# catalog.rego). Allow when the verb is in the role's EFFECTIVE actions for resource type "team"
# (categories expanded minus denials). Guarded by role_definition.permissions so a missing/malformed
# role definition default-denies. Using input.resource.type (always "team" for the dogfooded gates)
# rather than a literal keeps the rule generic and symmetric with the catalog policies.
allow if {
	input.role_definition.permissions
	verb in permissions.effective_actions(input.role_definition, input.resource.type)
}

# (2) Owner-only fence — define-roles / transfer-ownership are never categories and never delegatable
# (ADR 0007 + 0015). Keyed on the reserved, unspoofable owner CODE (not role_level): a custom role can
# carry any level but never the owner code, so no custom or non-owner system role can reach these two.
allow if {
	verb in {"define-roles", "transfer-ownership"}
	input.role_definition.code == "owner"
}
