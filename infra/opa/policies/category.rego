# Category resource authorization (per-type document).
#
# The app POSTs {"input": <AbacContext>} to /v1/data/category and reads result.allow.
# Decisions are role-definition-driven: the caller's role_definition.permissions grants
# COARSE permission categories (READ/WRITE/TAG/GRANT) per resource type, expanded to fine
# actions (view/list/create/update/delete/define-tags/assign-tags/assign-roles) through
# data.permission_categories and narrowed by denied_actions — the shared
# permissions.effective_actions (Phase 6.5, ADR 0007). A stale/unknown token expands to
# NOTHING (fail-closed ∅-expansion). JWT roles are a fallback used only when no role
# definition is present on the input, expanded through the SAME table.
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

import data.permissions

default allow := false

# final decision: a grant (direct or inherited via the resolved role) that is NOT denied. Slice B4
# (ADR 0018) removed the subject-roles fallback — membership is the sole access path; a request with
# no role_definition fails closed at every verb. Phase 5.5-A's inheritance + deny-overrides and the
# tag match + filter/bulk entrypoints are unchanged.
#   final_allow = (direct_grant OR inherited_grant) AND NOT denied
# Inheritance is OPT-IN, default-off via data.category.inheritable[<leaf>][<ancestor>] (absent ⇒ none),
# so with no inheritable data this behaves EXACTLY as the pre-hierarchy direct-only decision.
allow if {
	granted
	not denied
}

# COARSE LIST GATE (Phase 5.5-B): the type-level `@OpaPreAuthorize(<type>:list)` on a LIST endpoint asks
# `allow` with only a resource TYPE (no id, no ancestors) — so `inherited_grant` (which needs ancestors)
# can't fire, and a subject whose role grants the verb only on an inheritable ANCESTOR (e.g. a Catalog)
# would be denied at the gate before the SQL `subtreeSpec` widening can run. This clause lets such a
# subject pass the COARSE "may you list <type> at all" gate when its role's EFFECTIVE actions on a declared
# ancestor type contain the verb; the FINE which-rows cut still happens in SQL (the app-built subtreeSpec)
# — so this only OPENS the gate, never widens the rows. It is scoped to a LIST request (no resource id) so
# single-resource decisions are unchanged, and it is opt-in/default-off via the same
# data.category.inheritable. A true stranger (no role / no inheritable grant) is still denied.
allow if {
	not input.resource.id
	not denied
	list_inheritable_grant
}

list_inheritable_grant if {
	some ancestor_type, _ in data.category.inheritable[input.resource.type]
	verb in permissions.effective_actions(input.role_definition, ancestor_type)
}

# The fine action verb is the part after the ":" in input.action (e.g. "category:view" -> "view").
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

# Slice B4 (ADR 0018) — the blanket realm-role fallback was REMOVED. A category lives UNDER a
# governed catalog, so the resolved role (team membership) already applies at every level; the
# fallback only leaked. There is NO category:create-style narrow fallback here (categories/products
# are created under an already-governed catalog, where the resolved role grants `create`). A request
# with no role_definition now fails closed at every verb — single-GET of a category in a catalog the
# caller is not a member of is denied, closing the deep-link leak. See ADR 0018 §2b.

direct_grant if {
	verb in permissions.effective_actions(input.role_definition, input.resource.type)
	tags_satisfied
}

# An ancestor grant satisfies the leaf action when:
#   - the ancestor's type is declared inheritable for the leaf type (OPT-IN, default-off), and
#   - the root-resolved role's EFFECTIVE actions on that ancestor type contain the verb.
inherited_grant if {
	some ancestor in input.resource.ancestors
	data.category.inheritable[input.resource.type][ancestor.type]
	verb in permissions.effective_actions(input.role_definition, ancestor.type)
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
# Phase 6.5 — category expansion, INLINE (the PE-friendly idiom). `filter` decides "may this
# role LIST rows of this type": the role's category tokens for the (unknown) type expand through
# data.permission_categories to fine actions and must contain "list", minus an explicit denial.
# The expansion is written as a positive membership CHAIN — NOT a call to
# permissions.effective_actions — because OPA's partial evaluator does not inline user functions
# over an unknown argument: the call form leaves un-foldable comprehensions in the residual and
# every list would degrade to the batch fallback. Here the role and the table are fully known at
# compile time, so the whole chain folds to the type-eq tautology + the tag conditions (the 5.x
# residual shape, unchanged). Two consciously-accepted degradations, both FAIL-CLOSED to the
# batch recheck (which uses `allow` — wildcard-aware and denial-aware): a role carrying a
# DENIAL (the `not filter_list_denied` below survives PE as a negated type-eq → unsupported),
# and a raw "*"-keyed role (the resolve API expands wildcards before the compile call, so this
# does not occur on the wire path).

default filter := false

filter if {
	has_role_definition
	some token in input.role_definition.permissions[input.resource.type]
	"list" in data.permission_categories[token]
	not filter_list_denied
	filter_tags_satisfied
}

# The role explicitly withholds "list" for this type (deny-overrides at the list boundary).
filter_list_denied if {
	"list" in input.role_definition.denied_actions[input.resource.type]
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
