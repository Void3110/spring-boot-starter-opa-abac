# Catalog resource authorization (per-type document).
#
# The app POSTs {"input": <AbacContext>} to /v1/data/catalog and reads result.allow.
# Decisions are role-definition-driven: the caller's role_definition.permissions grants
# COARSE permission categories (READ/WRITE/TAG/GRANT) per resource type, expanded to fine
# actions (view/list/create/update/delete/define-tags/assign-tags/assign-roles) through
# data.permission_categories and narrowed by denied_actions — the shared
# permissions.effective_actions (Phase 6.5, ADR 0007). A stale/unknown token expands to
# NOTHING (fail-closed ∅-expansion).
#
# Slice B4 (ADR 0018) — membership is the sole access path. The blanket realm-role fallback
# (decide view/list/update/delete from JWT roles when no role_definition is present) was REMOVED:
# it granted every authenticated user access to every catalog and contradicted team-governance.
# The ONLY surviving JWT-role fallback is verb-gated to `catalog:create` (type-level onboarding,
# which precedes any team). The list path is the role-def-only `filter` entrypoint below + an
# app-supplied governed-id base scope. See ADR 0018.
#
# Phase 5.97 — tag-based grants (the category.rego block, ported as-is; retro-audit fold-in #3).
# A role may additionally REQUIRE tags: when input.role_definition.required_tags is present, the
# role-definition grant also requires that the resource's tags satisfy the requirement. The
# resource's tag values are at input.resource.attributes[<key>] — a scalar string or a string array.
#   ANY_OF  -> at least one required key matches  (existential: `some ... in`)
#   ALL_OF  -> every required key matches          (universal:   `every`)
# A role with no required_tags is vacuously satisfied (untagged roles behave exactly as before);
# a malformed required_tags / unknown match_mode fails the check -> deny (fail-closed). The tag
# conjunct only NARROWS the role-definition grant path (it never widens; the create fallback below
# carries no tag requirement, as creation precedes any resource).
#
# OPA 1.x: `if`/`in`/`contains`/`some`/`every` are built-in keywords — no imports needed. Default deny.

package catalog

import data.permissions

default allow := false

# The fine action verb is the part after the ":" in input.action (e.g. "catalog:view" -> "view").
verb := v if {
	parts := split(input.action, ":")
	count(parts) == 2
	v := parts[1]
}

# PRIMARY: role-definition-driven. Allow when the fine verb is in the role's EFFECTIVE actions
# for this resource type (categories expanded minus denials) AND the resource's tags satisfy
# the role's tag requirement.
allow if {
	verb in permissions.effective_actions(input.role_definition, input.resource.type)
	tags_satisfied
}

# NARROW CREATE FALLBACK (Slice B4): the ONLY surviving realm-role fallback. catalog:create is
# TYPE-level (no resourceId, no team/instance yet), so it cannot be team-scoped — a new user must be
# able to onboard a catalog BEFORE any team governs it. So the realm role catalog-editor grants
# `create` only; every other verb (view/list/update/delete/…) now requires a resolved role_definition
# (team membership). This is verb-gated by design: realm role = "may onboard new catalogs"; team
# membership = "what you may access". See ADR 0018 (membership-as-sole-access-path) §2d.
#
# The blanket realm fallbacks (catalog-viewer→READ, catalog-editor→READ+WRITE+TAG on ANY catalog)
# were REMOVED here in B4 — they leaked every catalog to every authenticated user and contradicted
# the team-governance model. Removal is unconditional (a fix, not a feature; no re-enable flag — the
# off-ramp would be the vuln).
allow if {
	verb == "create"
	not has_role_definition
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

# ---------------------------------------------------------------------------
# Slice B4 — list-filtering entrypoint (partial-evaluation friendly).
#
# `filter` is the rule the app's Compile API call partially-evaluates with the RESOURCE declared
# unknown (unknowns=["input.resource"]) so OPA returns the residual a row must satisfy — which the
# app composes as the catalog list's base scope (see CatalogListAuthorizer). Catalogs are NOT a
# tag-filtered resource here: visibility is a team-membership question resolved app-side
# (GovernedScopeResolver supplies the `id IN (governed ids)` base scope; this `filter` rule only
# decides "may this role LIST catalogs at all"). So the residual is unconditional ALLOW_ALL when the
# role grants `list`, and UNSATISFIABLE DENY_ALL otherwise — `governedScope ∧ ALLOW_ALL` = the
# governed set, `governedScope ∧ DENY_ALL` = empty.
#
# TWO deliberate properties (mirrors category.rego's filter — mx-cbd39e, mx-f63604):
#   1. ROLE-DEFINITION-ONLY — `filter` requires has_role_definition and has NO subject-roles fallback.
#      A list request with no role definition compiles to an UNSATISFIABLE residual -> DENY_ALL -> an
#      EMPTY list, never the whole table. This is the fail-closed boundary (the narrow catalog:create
#      fallback above lives ONLY on `allow`, never on `filter`).
#   2. PARTIAL-EVAL-FRIENDLY category expansion, INLINE — the role's category tokens for the (unknown)
#      type expand through data.permission_categories to fine actions and must contain "list", minus an
#      explicit denial. Written as a positive membership CHAIN, NOT a call to permissions.effective_actions,
#      because OPA's partial evaluator does not inline user functions over an unknown argument (the call
#      form leaves un-foldable comprehensions in the residual). Role + table are fully known at compile
#      time, so the chain folds to the type-eq tautology (ALLOW_ALL). A role carrying a DENIAL survives
#      PE as a negated type-eq (unsupported) and FAILS CLOSED to the batch recheck (which uses `allow`).

default filter := false

filter if {
	has_role_definition
	some token in input.role_definition.permissions[input.resource.type]
	"list" in data.permission_categories[token]
	not filter_list_denied
}

# The role explicitly withholds "list" for this type (deny-overrides at the list boundary).
filter_list_denied if {
	"list" in input.role_definition.denied_actions[input.resource.type]
}

# ---------------------------------------------------------------------------
# Phase 5 batch primitive — the bulk decision entrypoint, extended to catalog for action enrichment
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
