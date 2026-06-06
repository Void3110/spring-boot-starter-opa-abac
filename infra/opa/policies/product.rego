# Product resource authorization (per-type document).
#
# The app POSTs {"input": <AbacContext>} to /v1/data/product and reads result.allow.
# Decisions are role-definition-driven: the caller's role_definition.permissions grants
# action verbs per resource type. JWT roles are a fallback used only when no role
# definition is present on the input.
#
# Phase 5.5-A — N-level hierarchical inheritance + deny-overrides.
#   input.resource.ancestors is a root-first, leaf-excluded list of {type,id}. The role is
#   resolved ONCE on the governing root, so role_definition.permissions is keyed by ANCESTOR
#   type. A grant on an inheritable ancestor satisfies the leaf action:
#       final_allow = (direct_grant OR inherited_grant OR fallback) AND NOT denied
#   Inheritance is OPT-IN, default-off: an ancestor only counts when the relation is declared
#   in data.product.inheritable[<leaf type>][<ancestor type>] (absent ⇒ no inheritance, so a
#   policy with no inheritable data behaves EXACTLY as the pre-hierarchy direct-only decision).
#   Deny-overrides: an explicit leaf deny (input.resource.attributes.abac_deny == true) WINS
#   even when an ancestor would grant. Fail-closed: no/malformed ancestors ⇒ direct-only.
#
# OPA 1.x: `if`/`in`/`contains`/`some`/`every` are built-in keywords — no imports needed. Default deny.

package product

default allow := false

# final decision: a grant (direct, inherited, or the subject-roles fallback) that is NOT denied.
allow if {
	granted
	not denied
}

# The action verb is the part after the ":" in input.action (e.g. "product:read" -> "read").
verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# ---------------------------------------------------------------------------
# Grants (any one suffices; deny-overrides narrows afterward).
# ---------------------------------------------------------------------------

# PRIMARY: role-definition-driven direct grant — the verb is granted for THIS resource type.
granted if {
	direct_grant
}

# INHERITED: an inheritable ancestor's type carries the verb in the (root-resolved) role.
granted if {
	inherited_grant
}

# FALLBACK: only when no role definition is present, decide from subject roles.
# viewer/editor may read; only editor may write.
granted if {
	not has_role_definition
	verb == "read"
	some role in input.subject.roles
	role in {"catalog-viewer", "catalog-editor"}
}

granted if {
	not has_role_definition
	verb == "write"
	"catalog-editor" in input.subject.roles
}

direct_grant if {
	verb in input.role_definition.permissions[input.resource.type]
}

# An ancestor grant satisfies the leaf action when:
#   - the ancestor's type is declared inheritable for the leaf type (OPT-IN, default-off), and
#   - the root-resolved role grants the verb on that ancestor type.
inherited_grant if {
	some ancestor in input.resource.ancestors
	data.product.inheritable[input.resource.type][ancestor.type]
	verb in input.role_definition.permissions[ancestor.type]
}

has_role_definition if {
	input.role_definition.permissions
}

# ---------------------------------------------------------------------------
# Deny-overrides (the final narrowing AND): an explicit leaf deny wins over any grant.
# ---------------------------------------------------------------------------

denied if {
	input.resource.attributes.abac_deny == true
}
