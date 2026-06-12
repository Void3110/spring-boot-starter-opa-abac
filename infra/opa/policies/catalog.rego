# Catalog resource authorization (per-type document).
#
# The app POSTs {"input": <AbacContext>} to /v1/data/catalog and reads result.allow.
# Decisions are role-definition-driven: the caller's role_definition.permissions grants
# action verbs per resource type. JWT roles are a fallback used only when no role
# definition is present on the input.
#
# Phase 5.97 — tag-based grants (the category.rego block, ported as-is; retro-audit fold-in #3).
# A role may additionally REQUIRE tags: when input.role_definition.required_tags is present, the
# role-definition grant also requires that the resource's tags satisfy the requirement. The
# resource's tag values are at input.resource.attributes[<key>] — a scalar string or a string array.
#   ANY_OF  -> at least one required key matches  (existential: `some ... in`)
#   ALL_OF  -> every required key matches          (universal:   `every`)
# A role with no required_tags is vacuously satisfied (untagged roles behave exactly as before);
# a malformed required_tags / unknown match_mode fails the check -> deny (fail-closed). The
# subject-roles FALLBACK is untouched — the conjunct only NARROWS the role-definition grant path.
#
# OPA 1.x: `if`/`in`/`contains`/`some`/`every` are built-in keywords — no imports needed. Default deny.

package catalog

default allow := false

# The action verb is the part after the ":" in input.action (e.g. "catalog:read" -> "read").
verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# PRIMARY: role-definition-driven. Allow when the verb is granted for this resource type
# by the caller's role definition AND the resource's tags satisfy the role's tag requirement.
allow if {
	verb in input.role_definition.permissions[input.resource.type]
	tags_satisfied
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

# ---------------------------------------------------------------------------
# Tag-based grant (the Phase-4.5 match, ported from category.rego in Phase 5.97).
# ---------------------------------------------------------------------------

# The resource's value(s) for a tag key as a set: a scalar tag -> {scalar}; an array tag ->
# the set of its elements; an absent key -> the empty set.
resource_tag_values(key) := values if {
	value := input.resource.attributes[key]
	is_array(value)
	values := {v | some v in value}
}

resource_tag_values(key) := values if {
	value := input.resource.attributes[key]
	not is_array(value)
	values := {value}
}

resource_tag_values(key) := set() if {
	not input.resource.attributes[key]
}

# A single required key is satisfied when the resource's value(s) for it intersect the
# acceptable set (existential `some ... in`).
key_satisfied(key, acceptable) if {
	some v in resource_tag_values(key)
	v in acceptable
}

# ANY_OF: at least one required key is satisfied (existential).
tags_satisfied if {
	input.role_definition.match_mode == "ANY_OF"
	some key, acceptable in input.role_definition.required_tags
	key_satisfied(key, acceptable)
}

# ALL_OF: every required key is satisfied (universal).
tags_satisfied if {
	input.role_definition.match_mode == "ALL_OF"
	every key, acceptable in input.role_definition.required_tags {
		key_satisfied(key, acceptable)
	}
}

# Vacuous truth: a role with no tag requirement is unaffected (back-compat). This is the ONLY
# path that passes when required_tags is absent/empty; a present-but-malformed required_tags
# with an unknown/missing match_mode matches none of the rules above -> tags_satisfied fails -> deny.
tags_satisfied if {
	not has_required_tags
}

has_required_tags if {
	count(input.role_definition.required_tags) > 0
}
