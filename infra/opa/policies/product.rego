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
#       final_allow = (direct_grant OR inherited_grant) AND NOT denied
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
# a malformed required_tags / unknown match_mode fails the check -> deny (fail-closed). The tag
# conjunct only NARROWS the role-definition grant path (Slice B4 removed the subject-roles fallback;
# see the ADR-0018 note above).
#
# OPA 1.x: `if`/`in`/`contains`/`some`/`every` are built-in keywords — no imports needed. Default deny.

package product

import data.permissions

default allow := false

# final decision: a grant (direct or inherited via the resolved role) that is NOT denied. Slice B4
# removed the subject-roles fallback (membership is the sole access path).
allow if {
	granted
	not denied
}

# COARSE TYPE-LEVEL GATE (Slice B4): a type-level `@OpaPreAuthorize(product:create|list)` asks `allow` with
# only a resource TYPE (the new product has no id; a list has no instance). The gate resolves the caller's
# role on the parent catalog (the governing root, via the @OpaPreAuthorize roleResource override), so a
# subject whose role carries the verb on a declared inheritable ANCESTOR (catalog) passes. Verb-agnostic
# (create/list/assign-tags alike), scoped to a type-level request so single-resource decisions are
# unchanged; a non-member resolves no role and is denied. Product had no such gate before B4 (it is a leaf
# that is always listed under a category) — added here for the create/list type-level paths.
allow if {
	is_type_level_request
	not denied
	list_inheritable_grant
}

# Type-level request: id ABSENT or explicit `null` (the app serializes a Java null id as null) — both
# mean "type-level". `not input.resource.id` alone is UNDEFINED for an explicit null (Slice B4 null-safe).
is_type_level_request if not input.resource.id

is_type_level_request if input.resource.id == null

list_inheritable_grant if {
	membership_derived
	some ancestor_type, _ in data.product.inheritable[input.resource.type]
	verb in permissions.effective_actions(input.role_definition, ancestor_type)
}

# ADR 0031 — ANCESTOR INHERITANCE IS CONFINED TO MEMBERSHIP-DERIVED ROLES.
#
# Both inheritance clauses (this coarse type-level gate and the fine `inherited_grant`) open ONLY on a
# provenance stamp applied at the single membership funnel (EffectiveRoleService.resourceRole). Without
# it, a SYNTHESIZED role naming only an ancestor type — the supervised read scope's `catalog: ["READ"]`
# (ADR 0029), and the tiered/elevated roles of the later slices — would inherit `product:view` from the
# very catalog it may read, because inheritance keys on the VERB NAME across types and `READ` expands to
# `view, list, list-members` on both. Measured, not argued: before this conjunct the supervisor role
# returned allow=true for `product:view` with a catalog ancestor present, which is always the case at
# runtime. (An ancestor-LESS probe returns false, which is exactly how the defect survived planning.)
#
# DIRECT grants are untouched — a role naming this type explicitly still reaches it through
# `direct_grant` with no stamp at all. ABSENCE IS CLOSED: an unstamped role, an empty `attributes`, or
# an unknown provenance value fails this conjunct, so a future synthesized role that forgets the stamp
# fails closed rather than open. Mirrors category.rego exactly (per-type sibling parity).
membership_derived if {
	input.role_definition.attributes.provenance == "membership"
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

# Slice B4 (ADR 0018) — the blanket realm-role fallback was REMOVED. A product lives UNDER a governed
# catalog, so the resolved role (team membership) already applies at every level; the fallback only
# leaked. There is no product:create-style narrow fallback here (products are created under an
# already-governed catalog, where the resolved role grants `create`). A request with no role_definition
# now fails closed at every verb — single-GET of a product in a catalog the caller is not a member of
# is denied, closing the deep-link leak. See ADR 0018 §2b.

direct_grant if {
	verb in permissions.effective_actions(input.role_definition, input.resource.type)
	tags_satisfied
}

# An ancestor grant satisfies the leaf action when:
#   - the ancestor's type is declared inheritable for the leaf type (OPT-IN, default-off), and
#   - the root-resolved role's EFFECTIVE actions on that ancestor type contain the verb.
# Confined to membership-derived roles (ADR 0031) — see the note on `membership_derived` above.
inherited_grant if {
	membership_derived
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
# ADR 0030 §3–4 — THE PRODUCTION TIER, scoped to the supervised path.
#
# A supervisor (ADR 0029) reaches a catalog's CONTENTS through the ordinary direct grant on
# `category`/`product` that the synthesized role now carries — no inheritance, no ADR 0031
# involvement, no new allow clause. What decides how deep that oversight goes is the governing
# ROOT's `env` tag, carried here by root-attribute enrichment (ADR 0032) as
# input.resource.root_attributes. Two deny clauses, because deny-overrides is the corpus's
# strongest idiom: no allow clause anywhere can bypass them.
#
# BOTH clauses require `provenance == "supervised"`. That conjunct is not decoration — it is what
# makes ADR 0030 §2 structurally true: a MEMBERSHIP decision cannot reach either clause, so a member
# reading their own team's production catalog is unaffected, and stays unaffected even during an
# enrichment outage (when every supervised read closes).
#
# THE THREE INPUT STATES, and why this needs TWO clauses rather than one negation:
#   absent  -> the root was never established (enrichment failed) -> UNPROVEN -> closed, below;
#   {}      -> the root was fetched and carries no tags -> non-production (ADR 0030 §3) -> OPEN:
#              `not input.resource.root_attributes` is FALSE for {} (in Rego only false and
#              undefined are falsy), and `{}.env` is undefined, so neither clause fires;
#   tagged  -> as tagged.
#
# THE SHAPE TRAP, stated so it is not "simplified" later: a single naive clause
#   `not input.resource.root_attributes.env == "production"`
# is WRONG — an ABSENT env passes a negated comparison, so an enrichment outage would OPEN the tier
# instead of closing it. The absent state needs its own positive clause, which is the first one here.
#
# Deliberately NOT in `filter`: the list's tier decision lands at the coarse type-level gate (which
# consults `denied`), never in the SQL residual — a root_attributes predicate there would be a
# partial-evaluation dead end and a slice-boundary breach.

# Tier UNPROVEN — enrichment failed or was never attempted. An unproven tier is a closed tier.
denied if {
	input.role_definition.attributes.provenance == "supervised"
	not input.resource.root_attributes
}

# Tier proven PRODUCTION — oversight stops at the door until slice C's freshly-elevated exception.
# Shape-tolerant on cardinality: a tag value in this corpus is a scalar string OR a string array
# (the header contract `resource_tag_values` normalizes for), and a bare scalar `==` would fail
# OPEN on an array-shaped env — the SHAPE TRAP's cardinality twin. `root_env_values` mirrors
# `resource_tag_values` for the root map; an absent env leaves it undefined, which belongs to the
# untagged/open state ({} root) or the absent clause above (no root at all), never to this one.
denied if {
	input.role_definition.attributes.provenance == "supervised"
	"production" in root_env_values
}

# The root's env value(s) as a set: an array tag -> the set of its elements; a scalar -> {scalar}.
root_env_values := {v | some v in value} if {
	value := input.resource.root_attributes.env
	is_array(value)
}

root_env_values := {value} if {
	value := input.resource.root_attributes.env
	not is_array(value)
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
# List filtering entrypoint (partial-evaluation friendly) — the category.rego block, ported when
# products became taggable: the plain product list page lost its "rows have no policy variance"
# justification, so the which-rows cut moves into SQL exactly as for categories.
#
# `filter` is the rule the app's Compile API call partially-evaluates with the RESOURCE declared
# unknown (unknowns=["input.resource"]), so OPA returns the residual conditions a row must satisfy —
# which become a JPA Specification over the `tags` JSONB column.
#
# TWO deliberate differences from `allow` (see category.rego for the full prose):
#   1. ROLE-DEFINITION-ONLY — no subject-roles fallback; no role definition compiles to an
#      UNSATISFIABLE residual -> DENY_ALL -> an EMPTY list, never the whole table (fail-closed).
#   2. PARTIAL-EVAL-FRIENDLY tag match — `filter_tags_satisfied` uses the membership/equality pair
#      below so the residual reduces to a CLEAN predicate (eq / member) the residual translator
#      supports; the membership compiles to the `?` existence operator, matching BOTH a scalar and
#      an array tag — so the list and a single-GET agree on which rows are visible.
#
# The permission-category expansion is INLINE (the PE-friendly idiom): OPA's partial evaluator does
# not inline user functions over an unknown argument, so a permissions.effective_actions call would
# leave un-foldable comprehensions in the residual and every list would degrade to the batch
# fallback. The role + table are fully known at compile time, so the chain folds to the type-eq
# tautology + the tag conditions. A role carrying a DENIAL degrades FAIL-CLOSED to the batch
# recheck (the surviving `not filter_list_denied` is a negated type-eq → unsupported residual).
# ---------------------------------------------------------------------------

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

# A required key is satisfied for a SCALAR tag by equality, for an ARRAY tag by membership — two
# bodies so BOTH cases hold concretely AND the residual is a clean DNF:
#   `(attr == v)` -> jsonb_extract_path_text(...) = v   (scalar);
#   `v in attr`   -> jsonb_exists(...) (the `?` op)      (array — also matches a scalar string).
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
