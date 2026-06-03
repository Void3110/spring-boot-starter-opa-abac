# Team management authorization (the user-management-service dogfooding the starter).
#
# The service secures its OWN management API (membership, role-defs, transfer) with the same
# @OpaPreAuthorize mechanism it produces role definitions for. The app POSTs {"input": <AbacContext>}
# to /v1/data/team and reads result.allow. Decisions are role-definition-driven exactly like the
# catalog's per-type policies: the caller's effective role ON THIS TEAM is resolved server-side and
# its role_definition.permissions["team"] grants the management action verbs.
#
# Actions all share the "team:" prefix so the verb is a single clean token after the ":":
#   "team:manage"             (add/remove/update members)
#   "team:define-roles"       (define team-scoped custom role definitions)
#   "team:transfer-ownership"
# The capability ladder lives in the resolved role definition, not here:
#   owner         -> ["read", "manage", "define-roles", "transfer-ownership"]
#   administrator -> ["read", "manage"]
#   member/viewer -> ["read"]
#
# OPA 1.x: `if`/`in`/`contains` are built-in keywords — no imports needed. Default deny.

package team

default allow := false

# The action verb is the part after the ":" in input.action
# (e.g. "team:manage" -> "manage", "team:transfer-ownership" -> "transfer-ownership").
verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# Role-definition-driven: allow when the verb is granted for resource type "team" by the caller's
# resolved role definition. No JWT-role fallback — the user-service always resolves a role definition
# for the team being managed; absent one, the default deny stands.
allow if {
	has_role_definition
	verb in input.role_definition.permissions[input.resource.type]
}

has_role_definition if {
	input.role_definition.permissions
}
