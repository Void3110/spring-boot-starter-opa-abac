# Shared permission-category expansion (Phase 6.5, ADR 0007).
#
# THE one runtime home of the effective-set math. A role definition grants COARSE category
# tokens (READ / WRITE / TAG / GRANT / CONTROL) per resource type; the expansion table at
# data.permission_categories (permission_categories.json, colocated) maps each category to its
# FINE actions; denied_actions subtracts fine actions AFTER expansion (deny-overrides — denials
# only ever narrow). The fail-closed floor: an unknown/stale token (a pre-6.5 flat verb like
# "read", a typo, a removed category) expands to NOTHING — a role holding only stale tokens
# decides nothing.
#
# Wildcard lookup lives here: tokens come from role_def.permissions[<type>], falling back to
# role_def.permissions["*"] when the type key is ABSENT (an empty list is present — no fallback).
# The same fallback applies to denied_actions, mirroring the resolve API's wildcard expansion —
# without it, a "*"-scoped denial would not subtract on raw-row snapshots (role.rego assignable)
# and the actor's effective set would read WIDER than reality (an escalation, not a convenience).
#
# PARTIAL-EVAL CAVEAT (the deliberate split): these functions are for FULL evaluation (allow,
# inherited grants, role.rego assignable). OPA's partial evaluator does NOT inline user functions
# over an unknown argument, so a `filter` rule calling effective_actions(…, input.resource.type)
# leaves un-foldable calls in the residual → the parser rejects it → every list degrades to the
# batch fallback. The per-type `filter` rules therefore consume the SAME data.permission_categories
# table through an inline membership chain (see category.rego) — one expansion home, two
# consumption idioms.
#
# COPY-PORTABILITY: this module is deliberately self-contained — data.permission_categories is
# its only dependency — and gets copy-adapted into consumer corpora. Sibling references in these
# comments (category.rego, role.rego, the per-type `filter` rules) describe THIS repo's corpus
# and need not exist beside a copy.
#
# OPA 1.x: `if`/`in`/`some` are built-in keywords — no imports needed.

package permissions

# --- token lookup (wildcard-aware, total) ------------------------------------

# The granted category tokens for a type: the concrete key wins; the "*" wildcard applies only
# when the type key is absent; no key at all (or a malformed role_def) yields nothing.
#
# ABSENT means key PRESENCE, not truthiness. `false` is Rego's only falsy defined value: under
# a truthiness guard (`not role_def.permissions[type]`) a `false`-valued key satisfies the
# concrete-key clause (a defined value) AND the fallback guard at once — two clauses, two
# outputs, and OPA answers eval_conflict_error, which the data API surfaces as HTTP 500 rather
# than the deny this header promises. Testing the key keeps every present-but-malformed value
# (false, true, a scalar) on the concrete-key clause, where a non-array expands to NOTHING —
# the same fail-closed floor a stale token lands on. (External consumer review, 2026-08-23.)
tokens_for(role_def, type) := role_def.permissions[type]

tokens_for(role_def, type) := tokens if {
	permissions := object.get(role_def, "permissions", {})
	tokens := permissions["*"]
	not type in object.keys(permissions)
}

# The object.get binding makes this clause actually FIRE for a wholly-absent permissions map
# (a guard over object.keys(<absent ref>) never succeeds — measured; see the denied_for mirror
# below), so the header's totality claim holds by construction instead of leaning on the
# comprehension absorption in effective_actions. Decision-invisible either way — the grant axis
# lands on the empty expansion regardless — which makes this binding doctrine, not behavior
# (and its mutant equivalent). (Deep review 2026-08-24, round 2.)
tokens_for(role_def, type) := [] if {
	permissions := object.get(role_def, "permissions", {})
	is_object(permissions)
	not type in object.keys(permissions)
	not "*" in object.keys(permissions)
}

# The denied fine actions for a type — the wildcard-aware mirror of tokens_for, with one
# deliberate asymmetry: the denial axis is SHAPE-GUARDED. A malformed grant value only
# under-grants (it expands to nothing — the safe direction), but a malformed denial value
# would only under-DENY: dropping a configured subtraction is extra access, because
# deny-overrides exists to narrow BELOW the grants (ADR 0007). So a CONSULTED denial value
# that is not an array leaves this function UNDEFINED, and effective_actions deliberately
# propagates that (see the hoist note there): the whole answer collapses and every consumer
# lands on its default deny. Only the consulted lookup is validated — garbage under a type key
# this call never reads stays inert. (Deep review 2026-08-24; the first cut of this fix read
# present-but-malformed as "subtracts nothing", which is precisely the widening this guard
# exists to prevent.)
denied_for(role_def, type) := denied if {
	denied := role_def.denied_actions[type]
	is_array(denied)
}

denied_for(role_def, type) := denied if {
	denied := role_def.denied_actions["*"]
	is_array(denied)
	not type in object.keys(role_def.denied_actions)
}

# The binding through object.get is load-bearing: `object.keys(role_def.denied_actions)` with
# the map wholly ABSENT is an undefined ref inside the guard, and the clause then never fires —
# measured on 1.10.1 (the first cut of this fix had exactly that, masked by the comprehension
# absorption the hoist in effective_actions removed). Binding the default {} makes the
# absent-map case a DEFINED empty object, so this clause reliably answers [] for it.
# (is_object is intent-stating belt, not load-bearing: on a non-object map the object.keys
# guards already error->undefined and the clause cannot fire — an equivalent mutant, kept so
# the fail-closed intent survives any future builtin-semantics drift.)
denied_for(role_def, type) := [] if {
	denied_actions := object.get(role_def, "denied_actions", {})
	is_object(denied_actions)
	not type in object.keys(denied_actions)
	not "*" in object.keys(denied_actions)
}

# --- the effective set --------------------------------------------------------

# effective_actions(role_def, type): expand the granted category tokens through the table, then
# subtract the denials. Total on the GRANT axis — a missing/malformed role_def or type yields the
# EMPTY set (deny), and an unknown token contributes nothing — and deliberately NOT total on the
# denial axis: a malformed consulted denial leaves denied_for undefined and this function
# undefined with it, so every consumer lands on its default deny.
#
# THE HOIST IS LOAD-BEARING. `denied_list := denied_for(...)` must stay a direct binding OUTSIDE
# any comprehension: a set comprehension absorbs an undefined body into the EMPTY set (measured
# on OPA 1.10.1), so the previous `{a | some a in denied_for(...)}` shape would silently turn
# "malformed denial -> whole answer undefined" into "malformed denial -> subtract nothing" —
# the exact widening the denied_for shape guard exists to prevent.
effective_actions(role_def, type) := actions if {
	expanded := {action |
		some token in tokens_for(role_def, type)
		some action in data.permission_categories[token]
	}
	denied_list := denied_for(role_def, type)
	actions := expanded - {action | some action in denied_list}
}
