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
# ROOT-READ TAG EXEMPTION (ADR 0022): the catalog is the GOVERNING ROOT — the resource the
# team-target explicitly names. A role-level required_tags filter voiding that direct, admin-created
# binding on the named resource itself produces a navigation lockout (the member can't even see the
# catalog shell whose categories they may reach), while protecting only shell metadata — content
# below stays tag-filtered per row regardless. So READ-level verbs (view/list) on the root are
# exempt from the tag requirement WHEN data.config.root_read_tag_exemption is true (the shipped
# config.json default). Mutating verbs on the root and everything below the root stay tag-gated.
# An ABSENT flag means STRICT (no exemption) — config can only be widened by explicit deployment
# choice, never by a missing file (fail-closed).
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
# the role's tag requirement AND the resource is not explicitly denied (deny-overrides).
allow if {
	verb in permissions.effective_actions(input.role_definition, input.resource.type)
	tags_satisfied
	not denied
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
	not denied
}

# KEY PRESENCE, not truthiness (the recorded single-value escape, closing its last consumer):
# the bare reference left this rule undefined for `permissions: false`, and the NEGATED
# consumer (catalog's B4 create fallback) then treated a PRESENT resolved role as absent —
# reopening the realm branch for exactly the shape the corpus pins as present-blocks-fallback.
# Positive consumers (the filter guards) are unaffected: a malformed permissions value still
# dies in the token chain. (Deep review 2026-08-24, round 4.)
has_role_definition if {
	"permissions" in object.keys(input.role_definition)
}

# A present but NON-OBJECT role_definition also counts as "has one" — present-but-malformed
# must block the fallback, never widen (an explicit JSON null stays an honest ABSENT: the
# app serializes a missing role as null, exactly like the null resource id).
# (Deep review 2026-08-24, round 5.)
has_role_definition if {
	input.role_definition != null
	not is_object(input.role_definition)
}

# Deny-overrides (the final narrowing AND, ported from category.rego/product.rego): an explicit
# operator deny wins over any grant. This is the ROOT type's half of the ADR-0010 §3 invariant —
# single-GET and list must agree on "denied". The list side already honors it (the SQL residual
# AND-s `notDenied()` unconditionally in AbacQueryService, incl. catalogs); this restores the Rego
# side so a catalog flagged abac_deny=true is denied on view/update/delete too, not just hidden from
# the list. `abac_deny` is an operator-set flag (not client-writable — the tag dictionary allowlist
# rejects it), consistent with the mechanism category/product use. Leaf-scoped: it is a property of
# THIS resource's own tags, never inherited down a subtree (see category.rego / AbacQueryService).
denied if {
	input.resource.attributes.abac_deny == true
}

# ---------------------------------------------------------------------------
# ADR 0030 Amendment 4 (slice C) — THE SUPERVISED PATH IS HUMAN-ONLY.
#
# Supervision is a human ceremony: the reporting relation is between people. An agent-marked call is
# refused on the supervised path here too — the root type carries no tier (that lives on the
# children), so this file gets the agent deny and nothing else: no `elevated`, no `stepup_denied`, no
# `deny_reason`. A catalog read is metadata-only (ADR 0030 §1) and is never the sensitive act, so
# there is nothing here a second factor would open and nothing to challenge for.
#
# Provenance-scoped, exactly like the child files': a MEMBER's agent call cannot reach this clause,
# so the agent surface's existing member behaviour (ADR 0028) is untouched, as is the tool-gate —
# the tool-gate narrows, the target-gate decides, and this is the target gate gaining an input.
denied if {
	input.role_definition.attributes.provenance == "supervised"
	is_agent_call
}

# THE PRESENCE-TEST, not a truthiness test (the recorded escape: a bare reference is a truthiness
# test and Rego's only falsy value is `false`, so `act_chain: false` would leave the rule undefined
# and route the call to the wider human branch). Testing the KEY makes every value shape the claim
# can ARRIVE as — `false`, `[]`, `""`, `0` — an agent call. (A JSON-`null` claim is the one shape
# that never reaches here: the starter's extractor drops null-valued claims before the subject map
# is built, so the key is absent and the call reads as human — the extractor's contract, not this
# test's escape.) `act_chain` is the WIRE claim the `catalog-agent-*`
# clients' protocol mapper mints; `actor` is the MCP server's internal tool-gate attribute and never
# travels downstream.
is_agent_call if {
	"act_chain" in object.keys(input.subject.attributes)
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

# Key PRESENCE, not truthiness: `false` is Rego's only falsy defined value, so a truthiness
# guard here fires ALONGSIDE the singleton clause above (which happily binds `false`) — two
# clauses, two outputs, and OPA answers eval_conflict_error (the data API's HTTP 500) instead
# of deciding. Testing the key keeps a false-valued attribute on the singleton path, where
# {false} intersects no acceptable tag set -> the ordinary no-match deny. An absent key still
# yields the empty set. (External consumer review, 2026-08-23.)
resource_tag_values(key) := set() if {
	attributes := object.get(input.resource, "attributes", {})
	not key in object.keys(attributes)
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

# Vacuous truth: a role with no tag requirement is unaffected (back-compat). Alongside the
# root-read exemption below, these are the only paths that pass when the tag rules above don't;
# a present-but-malformed required_tags with an unknown/missing match_mode matches none of the
# match rules -> tags_satisfied fails -> deny (unless the verb is an exempt root read).
tags_satisfied if {
	not has_required_tags
}

# ROOT-READ TAG EXEMPTION (ADR 0022): membership IS the explicit grant on the resource the
# team-target names, so required_tags never gate READ-level navigation of the root itself.
# This package only ever decides catalogs (the root type), so the verb gate is the whole test.
# The flag is data, not input: concrete at partial-eval time, so the list residual folds clean.
tags_satisfied if {
	data.config.root_read_tag_exemption == true
	verb in {"view", "list"}
}

# A present required_tags key COUNTS AS a requirement whatever its shape: the old count()>0
# guard type-errored to undefined on a scalar (false, a number) and `not has_required_tags`
# then passed vacuously — silently dropping the whole configured tag narrowing on the decision
# AND the list residual (measured; deep review 2026-08-24, round 3 — the header always promised
# malformed -> deny). Keyed on presence, with one deliberate carve-out: a present EMPTY
# collection (object OR array) is "no requirement" — back-compat with the count()>0 reading,
# which read both as requirement-free (round 4: [] + ALL_OF would otherwise vacuously ALLOW
# through `every`, and [] + ANY_OF silently flipped to deny). Any other present non-object
# shape is a requirement no match rule can satisfy -> tags_satisfied fails -> deny.
has_required_tags if {
	"required_tags" in object.keys(input.role_definition)
	not empty_required_tags
}

empty_required_tags if {
	required := input.role_definition.required_tags
	is_object(required)
	count(required) == 0
}

empty_required_tags if {
	required := input.role_definition.required_tags
	is_array(required)
	count(required) == 0
}

# ---------------------------------------------------------------------------
# Slice B4 — list-filtering entrypoint (partial-evaluation friendly).
#
# `filter` is the rule the app's Compile API call partially-evaluates with the RESOURCE declared
# unknown (unknowns=["input.resource"]) so OPA returns the residual a row must satisfy — which the
# app composes as the catalog list's base scope (see CatalogListAuthorizer). Catalog visibility is
# primarily a team-membership question resolved app-side (GovernedScopeResolver supplies the
# `id IN (governed ids)` base scope), but a role may ALSO carry a tag requirement, and when it does
# the list residual must carry the tag predicate so `GET /catalogs` agrees with the single-GET
# `allow` (which requires `tags_satisfied`). Without the tag conjunct the residual folds to an
# unconditional ALLOW_ALL and a tag-gated role lists catalogs it may not view — the list-vs-GET
# divergence fixed here (7.0.5 baseline security review, catalog.rego High).
#
# THREE deliberate properties (mirrors category.rego's filter — mx-cbd39e, mx-f63604):
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
#   3. PARTIAL-EVAL-FRIENDLY tag match — `filter_tags_satisfied` expresses the grant as a membership
#      `v in input.resource.attributes[key]` (and a scalar-eq body) so the residual reduces to a CLEAN
#      predicate (eq / `?` existence) the residual translator supports, rather than the scalar-vs-array
#      `resource_tag_values` normalize (is_array / set-comprehension) used by the single-decision `allow`,
#      which does not reduce to SQL. A role with no required_tags is vacuously satisfied -> ALLOW_ALL, so
#      untagged catalog roles behave exactly as before (back-compat).

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

# A malformed consulted denial fails the LIST path closed too: `in` over a non-collection is
# undefined, so without this clause the membership guard above silently DROPS the denial and
# the residual is wider than the decision (the allow path collapses to deny on the same shape
# via permissions.denied_for).
#
# PARTIAL-EVAL NOTE (corrected in round 3): input.resource — including .type — is the UNKNOWN
# (the app compiles with unknowns=["input.resource"]), so these clauses do NOT fold to
# constants; they fold to (negated) type-eq guards over the enumerated denied_actions keys, and
# a fired guard makes the residual unsupported -> the client degrades to the batch
# allow-recheck (fail-closed by the documented degradation path). Only a denial-free role's
# residual keeps its unchanged shape. (Deep review 2026-08-24, rounds 2-3.)
filter_list_denied if {
	denied := input.role_definition.denied_actions[input.resource.type]
	not is_array(denied)
}

# A NON-OBJECT denied_actions map is undefined under BOTH clauses above (indexing a non-object
# is undefined), which would leave the residual WIDER than the decision — the allow side
# collapses on the same shape via denied_for's object guards. The binding keeps a wholly-absent
# map non-denying: an absent ref leaves `denied_actions` unbound and this clause never fires.
# (Deep review 2026-08-24, round 3.)
filter_list_denied if {
	denied_actions := input.role_definition.denied_actions
	not is_object(denied_actions)
}

# No required tags -> vacuously true (an unconditional residual -> ALLOW_ALL for this subject).
filter_tags_satisfied if {
	not has_required_tags
}

# ROOT-READ TAG EXEMPTION (ADR 0022): `filter` IS the root list path (the "list" chain above), so
# with the exemption on, the tag conjunct drops from the list residual entirely. The flag is DATA —
# concrete at compile time — so this branch folds to TRUE (never a residual condition) and the
# governed-id base scope alone decides, matching the exempt single-GET `allow`. List and view agree
# in BOTH flag states: strict -> both tag-gated; exempt -> both membership-only.
filter_tags_satisfied if {
	data.config.root_read_tag_exemption == true
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
