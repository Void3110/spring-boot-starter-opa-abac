# Product resource authorization (per-type document).
#
# The app POSTs {"input": <AbacContext>} to /v1/data/product and reads result.allow.
# Decisions are role-definition-driven: the caller's role_definition.permissions grants
# action verbs per resource type. JWT roles are a fallback used only when no role
# definition is present on the input.
#
# OPA 1.x: `if`/`in`/`contains` are built-in keywords — no imports needed. Default deny.

package product

default allow := false

# The action verb is the part after the ":" in input.action (e.g. "product:read" -> "read").
verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# PRIMARY: role-definition-driven. Allow when the verb is granted for this resource type
# by the caller's role definition.
allow if {
	verb in input.role_definition.permissions[input.resource.type]
}

# FALLBACK: only when no role definition is present, decide from subject roles.
# viewer/editor may read; only editor may write.
allow if {
	not has_role_definition
	verb == "read"
	some role in input.subject.roles
	role in {"catalog-viewer", "catalog-editor"}
}

allow if {
	not has_role_definition
	verb == "write"
	"catalog-editor" in input.subject.roles
}

has_role_definition if {
	input.role_definition.permissions
}
