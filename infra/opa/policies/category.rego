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
	is_type_level_request
	not denied
	list_inheritable_grant
}

# A type-level request (a list, or a create/assign-tags before the instance exists) carries NO resource
# id. The app serializes that as an ABSENT key OR an explicit `null` (a Java `null` id) — both mean
# "type-level", so this helper accepts either. (`not input.resource.id` alone is UNDEFINED — not true —
# for an explicit `null`, which silently closed the coarse type-level gate; Slice B4 made it null-safe.)
is_type_level_request if not input.resource.id

is_type_level_request if input.resource.id == null

# The generic clause above is verb-agnostic: it lets ANY type-level verb (list, create, assign-tags)
# pass when the caller's role — resolved on the inheritable ANCESTOR (the parent catalog, via the
# @OpaPreAuthorize roleResource override, Slice B4) — carries that verb. So a member with WRITE/TAG on the
# catalog can create/tag categories; a non-member resolves no role and is denied. No verb-specific clause
# is needed (the original Phase-5.5-B "list" naming is historical — it gates every type-level verb).
list_inheritable_grant if {
	membership_derived
	some ancestor_type, _ in data.category.inheritable[input.resource.type]
	verb in permissions.effective_actions(input.role_definition, ancestor_type)
}

# ADR 0031 — ANCESTOR INHERITANCE IS CONFINED TO MEMBERSHIP-DERIVED ROLES.
#
# Both inheritance clauses (this coarse type-level gate and the fine `inherited_grant`) open ONLY on a
# provenance stamp applied at the single membership funnel (EffectiveRoleService.resourceRole). Without
# it, a SYNTHESIZED role naming only an ancestor type — the supervised read scope's `catalog: ["READ"]`
# (ADR 0029), and the tiered/elevated roles of the later slices — would inherit `category:view` from the
# very catalog it may read, because inheritance keys on the VERB NAME across types and `READ` expands to
# `view, list, list-members` on both. Measured, not argued: before this conjunct the supervisor role
# returned allow=true for `category:view` and `product:view` with a catalog ancestor present, which is
# always the case at runtime. (An ancestor-LESS probe returns false, which is exactly how the defect
# survived planning — a passing test that never matched the request being authorized.)
#
# DIRECT grants are untouched: a role naming this type explicitly still reaches it through
# `direct_grant` with no stamp at all. That is why every shipped per-type role is unaffected, and why a
# future slice that widens the synthesized role by naming `category`/`product` explicitly needs no
# policy change. ABSENCE IS CLOSED — an unstamped role, an empty `attributes`, or an unknown provenance
# value simply fails this conjunct, so a future synthesized role that forgets the stamp fails closed (a
# visible missing-access bug) rather than open.
#
# NOT centralized into permissions.effective_actions: direct grants use the same helper (the supervisor
# would lose its own catalog:view), and permissions.rego is byte-mirrored into the user-service bundle —
# a drift surface for no benefit. category.rego/product.rego are not mirrored.
membership_derived if {
	input.role_definition.attributes.provenance == "membership"
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
# Confined to membership-derived roles (ADR 0031) — see the note on `membership_derived` above.
inherited_grant if {
	membership_derived
	some ancestor in input.resource.ancestors
	data.category.inheritable[input.resource.type][ancestor.type]
	verb in permissions.effective_actions(input.role_definition, ancestor.type)
}

has_role_definition if {
	input.role_definition.permissions
}

# ---------------------------------------------------------------------------
# DENY-OVERRIDES, in two halves (slice C, ADR 0030 §6 as amended 2026-08-13).
#
# `denied` is the single narrowing AND consulted by both allow clauses and by `bulk`. Slice C splits
# it because the structured `deny_reason` below must be emitted ONLY when step-up is the SOLE blocker:
#
#   denied = stepup_denied  OR  denied_other
#
# `denied_other` is, by construction, "every deny EXCEPT the step-up clause" — which is exactly what
# the sole-blocker rule has to negate. ADD ANY NEW DENY CLAUSE TO `denied_other`, NEVER TO `denied`:
# a deny class that lands directly on `denied` is invisible to `not denied_other` and would leak a
# challenge to a subject a second factor cannot help.
# ---------------------------------------------------------------------------

denied if {
	denied_other
}

denied if {
	stepup_denied
}

# An explicit leaf deny wins over any grant.
denied_other if {
	input.resource.attributes.abac_deny == true
}

# ---------------------------------------------------------------------------
# ADR 0030 §3–4 — THE PRODUCTION TIER, scoped to the supervised path (+ §5–7's elevation, slice C).
#
# A supervisor (ADR 0029) reaches a catalog's CONTENTS through the ordinary direct grant on
# `category`/`product` that the synthesized role now carries — no inheritance, no ADR 0031
# involvement, no new allow clause. What decides how deep that oversight goes is the governing
# ROOT's `env` tag, carried here by root-attribute enrichment (ADR 0032) as
# input.resource.root_attributes. Deny clauses, because deny-overrides is the corpus's strongest
# idiom: no allow clause anywhere can bypass them. Slice C adds a third (the agent deny) and narrows
# the production one by `not elevated` — the unproven one is untouched, on purpose.
#
# EVERY clause requires `provenance == "supervised"`. That conjunct is not decoration — it is what
# makes ADR 0030 §2 structurally true: a MEMBERSHIP decision cannot reach any clause, so a member
# reading their own team's production catalog is unaffected, and stays unaffected even during an
# enrichment outage (when every supervised read closes) and on an agent-marked call.
#
# THE THREE INPUT STATES, and why this needs TWO tier clauses rather than one negation:
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
#
# ELEVATION-PROOF BY OMISSION (ADR 0030 Amendment 2, 2026-08-13): this clause gains NO `not elevated`
# conjunct, deliberately. Elevation proves who is present; it never proves what the tier is, so an
# enrichment outage stays closed for a freshly-elevated supervisor exactly as for anyone else. It also
# keeps this clause in `denied_other`, so an outage answers a plain 403 and never a challenge a second
# factor could not satisfy.
denied_other if {
	input.role_definition.attributes.provenance == "supervised"
	not input.resource.root_attributes
}

# ADR 0030 Amendment 4 — THE SUPERVISED PATH IS HUMAN-ONLY. Supervision and elevation are human
# ceremonies: the reporting relation is between people and the second factor proves a person is
# present. An agent-marked call is refused on the supervised path at ANY tier — production, staging,
# untagged, unproven alike — and, being in `denied_other`, always plainly (no challenge for a caller
# that cannot TOTP; a challenge here would be ADR 0030 §7's loop). Provenance-scoped like every other
# clause here, so a MEMBER's agent call cannot reach it and the agent surface's member behaviour is
# untouched. The tool-gate (agent_tools.rego) is not this file's business — ADR 0028's line holds:
# the tool-gate narrows, the target-gate decides; this is the target gate gaining an input.
denied_other if {
	input.role_definition.attributes.provenance == "supervised"
	is_agent_call
}

# Tier proven PRODUCTION — oversight stops at the door UNLESS the subject is freshly elevated.
# Shape-tolerant on cardinality: a tag value in this corpus is a scalar string OR a string array
# (the header contract `resource_tag_values` normalizes for), and a bare scalar `==` would fail
# OPEN on an array-shaped env — the SHAPE TRAP's cardinality twin. `root_env_values` mirrors
# `resource_tag_values` for the root map; an absent env leaves it undefined, which belongs to the
# untagged/open state ({} root) or the absent clause above (no root at all), never to this one.
#
# Factored into its own rule (rather than sitting inline on `denied`) because the sole-blocker
# `deny_reason` below needs to name exactly this clause and nothing else.
stepup_denied if {
	input.role_definition.attributes.provenance == "supervised"
	"production" in root_env_values
	not elevated
}

# ---------------------------------------------------------------------------
# ADR 0030 §5 — ELEVATION. Resource-server-side freshness is the WHOLE control: `acr` and `auth_time`
# are fixed at authentication and a refresh grant preserves both (measured on the rig), so a short
# token lifetime would prove nothing and only `now - auth_time` can force re-authentication.
#
# FAIL-CLOSED BY CONSTRUCTION, three ways, none of which needs a guard clause:
#   - a missing or unmapped `acr` leaves the `data.step_up.loa` lookup undefined;
#   - a missing `auth_time` leaves the arithmetic undefined;
#   - a non-numeric `auth_time` (a string, say) makes the subtraction a type error -> undefined.
# In every case `elevated` is undefined, `not elevated` holds, and the production deny stands. The
# clock is OPA's OWN (`time.now_ns`, mocked in tests to pin the window), and `skew` is explicit
# rather than implied.
#
# The window is bounded on BOTH sides: `skew` tolerates clock disagreement in either direction, but
# an `auth_time` further ahead of OPA's clock than `skew` fails closed — without the lower bound a
# future-stamped `auth_time` (an IdP host clock running ahead) would satisfy the one-sided `<=` by an
# arbitrary margin and stay elevated for the life of the session, refresh after refresh.
#
# `not is_agent_call` is defence in depth beside the agent deny above: elevation is a human ceremony,
# so no combination of claims lets an agent-marked call elevate even if the deny were ever relaxed.
# The level comparison is TYPE-GUARDED and TIED TO THE ADVERTISED ACR. Rego comparisons are a
# total order ACROSS types (every string sorts above every number, so `"1" >= 2` is true): without
# `is_number`, a string-valued `loa` entry — a natural transcription slip from the realm's
# `acr.loa.map`, which is itself a JSON string — would elevate password-only logins instead of
# failing closed. And the threshold is `loa[required_acr]`, never a literal: the level the
# challenge advertises is by construction the level this check demands, so the pair cannot drift
# apart (a literal `2` beside `required_acr: "aal3"` would under-enforce; beside an unmapped name
# it would challenge for an ACR that elevates nothing — the §7 loop).
elevated if {
	not is_agent_call
	level := data.step_up.loa[input.subject.attributes.acr]
	is_number(level)
	required_level := data.step_up.loa[data.step_up.required_acr]
	is_number(required_level)
	level >= required_level
	(time.now_ns() / 1000000000) - input.subject.attributes.auth_time <= data.step_up.max_age + data.step_up.skew
	input.subject.attributes.auth_time - (time.now_ns() / 1000000000) <= data.step_up.skew
}

# THE PRESENCE-TEST, not a truthiness test (the recorded escape: a bare `input.subject.attributes.x`
# reference is a truthiness test, and Rego's ONLY falsy value is `false` — so `act_chain: false` would
# leave the rule undefined and route the call to the wider human branch). Testing the KEY makes every
# value shape the claim can ARRIVE as — `false`, `[]`, `""`, `0` — an agent call. (A JSON-`null`
# claim is the one shape that never reaches here: the starter's extractor drops null-valued claims
# before the subject map is built, so the key is absent and the call reads as human. That is the
# extractor's contract, not this test's escape.) `act_chain` is the WIRE claim minted
# by the `catalog-agent-*` clients' protocol mapper; `actor` is the MCP server's internal tool-gate
# attribute and never travels downstream.
is_agent_call if {
	"act_chain" in object.keys(input.subject.attributes)
}

# ADR 0030 §6–7 + Amendment 1 — THE SOLE-BLOCKER `deny_reason`.
#
# Emitted iff the subject is `granted`, the step-up clause fires, and NO other deny fires — i.e. the
# subject is exactly one elevation away from allow. Everything ADR 0030 §7 asks for falls out of that
# one rule rather than out of three separate checks:
#   - an out-of-scope supervisor gets a plain 403 (not `granted`) — no "this is production" leak;
#   - an elevated supervisor's WRITE gets a plain 403 (not `granted` — the read-only ceiling is not
#     an elevation problem);
#   - an agent call gets a plain 403 (the agent deny is a `denied_other`) — no unfulfillable TOTP
#     prompt, so no challenge loop;
#   - an enrichment outage gets a plain 403 (the unproven clause is a `denied_other`).
# `max_age` AND `required_acr` come from the same data the check uses, so the challenge can never
# advertise a window the policy does not enforce — nor an ACR name the `loa` map no longer accepts
# (a literal here would survive a level-2 rename in `data.step_up.loa` and send the client into the
# §7 re-auth loop: challenged with a name that never maps to level 2, elevates nothing, challenged
# again). An absent `data.step_up` (or a missing key) leaves this undefined -> plain deny.
deny_reason := {
	"type": "insufficient_user_authentication",
	"required_acr": data.step_up.required_acr,
	"max_age": data.step_up.max_age,
} if {
	stepup_denied
	granted
	not denied_other

	# The challenge is only minted when answering it would actually elevate: `required_acr` must map
	# to a NUMERIC level in `loa` — that one IS decisive, because a comparison silently ORDERS across
	# types instead of erroring. The two window `is_number`s below are deliberate belt-and-braces and
	# are NOT decisive: the `max_age + skew` conjunct further down is arithmetic, and arithmetic on a
	# non-number is a type error → the rule is undefined → the challenge is already muted (measured:
	# deleting either one leaves the suite green). They are kept because that subsumption rests on a
	# subtle asymmetry — `+` errors where `>=` quietly orders — and stating the type requirement in
	# the rule is cheaper than re-deriving it. Incoherent data on any axis mutes the challenge.
	is_number(data.step_up.loa[data.step_up.required_acr])
	is_number(data.step_up.max_age)
	is_number(data.step_up.skew)

	# …and the challenge must be answerable BY A FRESH RE-AUTHENTICATION, which is what it asks for.
	# `elevated` has TWO freshness conjuncts, so this needs two guards, one per axis:
	#   now - auth_time <= max_age + skew   (the upper bound — the sum)
	#   auth_time - now <= skew             (the lower bound — skew ALONE)
	# Guarding only `max_age >= 0` was wrong (max_age=-1 with skew=30 still elevates); guarding only
	# the SUM is also wrong, and in the more dangerous direction: skew=-10 with max_age=300 leaves a
	# positive sum, so a challenge IS minted, yet a re-auth at age 0 fails `0 <= -10` and the subject
	# is challenged again — ADR 0030 §7's loop, in the rule written to prevent it. A fresh re-auth
	# (age 0) clears the deny iff `skew >= 0 AND max_age + skew > 0`; that is exactly this pair.
	data.step_up.skew >= 0
	data.step_up.max_age + data.step_up.skew > 0
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
