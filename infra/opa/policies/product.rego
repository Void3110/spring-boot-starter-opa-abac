# Product resource authorization (per-type document).
#
# The app POSTs {"input": <AbacContext>} to /v1/data/product and reads result.allow.
# Decisions are role-definition-driven: the caller's role_definition.permissions grants
# COARSE permission categories (READ/WRITE/TAG/GRANT) per resource type, expanded to fine
# actions (view/list/create/update/delete/define-tags/assign-tags/assign-roles) through
# data.permission_categories and narrowed by denied_actions — the shared
# permissions.effective_actions (Phase 6.5, ADR 0007). A stale/unknown token expands to
# NOTHING (fail-closed ∅-expansion). JWT roles are a fallback used only when no role
# definition is present on the input, expanded through the SAME table.
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
# Phase 5.97 — tag-based grants (the category.rego block, ported as-is; retro-audit fold-in #3).
# A role may additionally REQUIRE tags: when input.role_definition.required_tags is present,
# a role-definition grant (direct or inherited) also requires that the resource's tags satisfy
# the requirement. The resource's tag values are at input.resource.attributes[<key>] — a scalar
# string or a string array.
#   ANY_OF  -> at least one required key matches  (existential: `some ... in`)
#   ALL_OF  -> every required key matches          (universal:   `every`)
# A role with no required_tags is vacuously satisfied (untagged roles behave exactly as before);
# a malformed required_tags / unknown match_mode fails the check -> deny (fail-closed). The
# subject-roles FALLBACK is untouched — the conjunct only NARROWS the role-definition grant path.
#
# OPA 1.x: `if`/`in`/`contains`/`some`/`every` are built-in keywords — no imports needed. Default deny.

package product

import data.permissions

default allow := false

# final decision: a grant (direct, inherited, or the subject-roles fallback) that is NOT denied.
allow if {
	granted
	not denied
}

# The fine action verb is the part after the ":" in input.action (e.g. "product:view" -> "view").
verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# ---------------------------------------------------------------------------
# Grants (any one suffices; deny-overrides narrows afterward).
# ---------------------------------------------------------------------------

# PRIMARY: role-definition-driven direct grant — the verb is granted for THIS resource type AND
# the resource's tags satisfy the role's tag requirement.
granted if {
	direct_grant
}

# INHERITED: an inheritable ancestor's type carries the verb in the (root-resolved) role. The leaf's
# tag requirement still applies (a tag-gated role only grants where the leaf's tags satisfy it).
granted if {
	inherited_grant
	tags_satisfied
}

# FALLBACK: only when no role definition is present, decide from subject roles — through the
# same expansion table. catalog-viewer reaches READ (view/list); catalog-editor reaches
# READ+WRITE+TAG (pre-6.5 "write" implied tag-setting — the reach is preserved, ADR 0007).
granted if {
	not has_role_definition
	some role in input.subject.roles
	role in {"catalog-viewer", "catalog-editor"}
	verb in permissions.effective_from_categories({"READ"})
}

granted if {
	not has_role_definition
	"catalog-editor" in input.subject.roles
	verb in permissions.effective_from_categories({"READ", "WRITE", "TAG"})
}

direct_grant if {
	verb in permissions.effective_actions(input.role_definition, input.resource.type)
	tags_satisfied
}

# An ancestor grant satisfies the leaf action when:
#   - the ancestor's type is declared inheritable for the leaf type (OPT-IN, default-off), and
#   - the root-resolved role's EFFECTIVE actions on that ancestor type contain the verb.
inherited_grant if {
	some ancestor in input.resource.ancestors
	data.product.inheritable[input.resource.type][ancestor.type]
	verb in permissions.effective_actions(input.role_definition, ancestor.type)
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

# ---------------------------------------------------------------------------
# Phase 5 batch primitive — the bulk decision entrypoint, extended to product for action enrichment
# (Phase 6). `bulk` evaluates `allow` for each item in a list input ({"input": {"items": [<ctx>, …]}})
# and returns a positional list of booleans — the same shared primitive the data-filtering allowlist
# uses, mirrored byte-for-byte from category.rego. Each item carries its own resource, so `allow` runs
# per item with the full single-decision logic — fail-closed per element. ADDITIVE: it adds no new
# decision, it maps the existing `allow` over a list.
# ---------------------------------------------------------------------------

bulk := [decision |
	some item in input.items
	decision := allow with input as item
]
