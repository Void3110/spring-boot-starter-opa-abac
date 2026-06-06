# Category resource authorization (per-type document).
#
# The app POSTs {"input": <AbacContext>} to /v1/data/category and reads result.allow.
# Decisions are role-definition-driven: the caller's role_definition.permissions grants
# action verbs per resource type. JWT roles are a fallback used only when no role
# definition is present on the input.
#
# Phase 4.5 — tag-based grants. A role may additionally REQUIRE tags: when
# input.role_definition.required_tags is present, `allow` requires BOTH the permission AND
# that the resource's tags satisfy the requirement (matched here, in Rego). The resource's
# tag values are at input.resource.attributes[<key>] — a scalar string or a string array.
#   ANY_OF  -> at least one required key matches  (existential: `some ... in`)
#   ALL_OF  -> every required key matches          (universal:   `every`)
# A role with no required_tags is vacuously satisfied, so untagged roles behave exactly as
# before. A malformed required_tags / unknown match_mode fails the check -> deny (fail-closed).
#
# OPA 1.x: `if`/`in`/`contains`/`every`/`some` are built-in keywords — no imports needed. Default deny.

package category

default allow := false

# final decision: a grant (direct, inherited, or the subject-roles fallback) that is NOT denied.
# Phase 5.5-A added inheritance + deny-overrides; the tag match + filter/bulk entrypoints are unchanged.
#   final_allow = (direct_grant OR inherited_grant OR fallback) AND NOT denied
# Inheritance is OPT-IN, default-off via data.category.inheritable[<leaf>][<ancestor>] (absent ⇒ none),
# so with no inheritable data this behaves EXACTLY as the pre-hierarchy direct-only decision.
allow if {
	granted
	not denied
}

# The action verb is the part after the ":" in input.action (e.g. "category:read" -> "read").
verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# PRIMARY: role-definition-driven direct grant. The verb is granted for THIS resource type by the
# caller's role definition AND the resource's tags satisfy the role's tag requirement.
granted if {
	direct_grant
}

# INHERITED: an inheritable ancestor's type carries the verb in the (root-resolved) role. The leaf's
# tag requirement still applies (a tag-gated role only grants where the leaf's tags satisfy it).
granted if {
	inherited_grant
	tags_satisfied
}

# FALLBACK: only when no role definition is present, decide from subject roles.
# viewer/editor may read; only editor may write. (No tag requirement applies to the fallback.)
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
	tags_satisfied
}

# An ancestor grant satisfies the leaf action when:
#   - the ancestor's type is declared inheritable for the leaf type (OPT-IN, default-off), and
#   - the root-resolved role grants the verb on that ancestor type.
inherited_grant if {
	some ancestor in input.resource.ancestors
	data.category.inheritable[input.resource.type][ancestor.type]
	verb in input.role_definition.permissions[ancestor.type]
}

has_role_definition if {
	input.role_definition.permissions
}

# Deny-overrides (the final narrowing AND): an explicit leaf deny wins over any grant.
denied if {
	input.resource.attributes.abac_deny == true
}

# ---------------------------------------------------------------------------
# Tag-based grant (the Phase-4.5 match — evaluated here, in the policy).
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

# ---------------------------------------------------------------------------
# Phase 5 — list filtering entrypoint (partial-evaluation friendly).
#
# `filter` is the rule the app's Compile API call partially-evaluates with the RESOURCE declared
# unknown (unknowns=["input.resource"]), so OPA returns the residual conditions a row must satisfy —
# which become a JPA Specification over the `tags` JSONB column.
#
# TWO deliberate differences from `allow`:
#   1. ROLE-DEFINITION-ONLY — `filter` requires has_role_definition and has NO subject-roles fallback
#      (unlike `allow`, which grants read from JWT roles when no role definition is present). A list
#      request with no role definition therefore compiles to an UNSATISFIABLE residual -> DENY_ALL ->
#      an EMPTY list, never the whole table. This is the fail-closed boundary (modeled on team.rego).
#   2. PARTIAL-EVAL-FRIENDLY tag match — `filter_tags_satisfied` expresses the grant as a membership
#      `v in input.resource.attributes[key]` so the residual reduces to a CLEAN predicate (eq / member)
#      the residual translator supports, rather than the scalar-vs-array `resource_tag_values` normalize
#      (is_array / set-comprehension) used by the single-decision `allow`, which does not reduce to SQL.
#      The membership compiles to the `?` existence operator, which matches BOTH a scalar tag and an
#      array tag — so the list and a single-GET agree on which rows are visible.
#
# Flat-verb only: `filter` matches the current `category:read` verb. Coarse category expansion
# (READ/WRITE/TAG/GRANT) is a later, additive retrofit (ADR 0007 / Phase 6.5) — not anticipated here.

default filter := false

filter if {
	has_role_definition
	"read" in input.role_definition.permissions[input.resource.type]
	filter_tags_satisfied
}

# No required tags -> vacuously true (an unconditional residual -> ALLOW_ALL for this subject).
filter_tags_satisfied if {
	not has_required_tags
}

# A required key is satisfied when an acceptable value matches the resource's tag — for a SCALAR tag by
# equality, for an ARRAY tag by membership. Two bodies so BOTH cases hold concretely AND the residual is
# a clean DNF: `(attr == v)`  ->  jsonb_extract_path_text(...) = v   (scalar);
#              `v in attr`    ->  jsonb_exists(...) (the `?` op)      (array — also matches a scalar string).
# Either branch alone matches what the single-decision `allow` matches, so list and single-GET agree.
filter_key_satisfied(key, acceptable) if {
	some v in acceptable
	input.resource.attributes[key] == v
}

filter_key_satisfied(key, acceptable) if {
	some v in acceptable
	v in input.resource.attributes[key]
}

# ANY_OF: SOME required key is satisfied.
filter_tags_satisfied if {
	input.role_definition.match_mode == "ANY_OF"
	some key, acceptable in input.role_definition.required_tags
	filter_key_satisfied(key, acceptable)
}

# ALL_OF: every required key is satisfied.
filter_tags_satisfied if {
	input.role_definition.match_mode == "ALL_OF"
	every key, acceptable in input.role_definition.required_tags {
		filter_key_satisfied(key, acceptable)
	}
}

# ---------------------------------------------------------------------------
# Phase 5 — bulk decision entrypoint (the post-fetch allowlist + the batch primitive).
#
# `bulk` evaluates `allow` for each item in a list input ({"input": {"items": [<ctx>, …]}}) and returns
# a positional list of booleans. The same shared primitive backs the data-filtering allowlist finisher
# and (later) action enrichment. Each item carries its own resource, so `allow` runs per item with the
# full single-decision logic (incl. the tag match) — fail-closed per element.
# ---------------------------------------------------------------------------

bulk := [decision |
	some item in input.items
	decision := allow with input as item
]
