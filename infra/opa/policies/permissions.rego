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

tokens_for(role_def, type) := role_def.permissions["*"] if {
	not type in object.keys(role_def.permissions)
}

tokens_for(role_def, type) := [] if {
	not type in object.keys(role_def.permissions)
	not "*" in object.keys(role_def.permissions)
}

# The denied fine actions for a type — the exact mirror of tokens_for (see the header note on
# why denials must be wildcard-aware too), key-presence guards included. Mirroring makes the
# present-key-wins rule uniform, with one consequence worth stating: a present-but-malformed
# denial value behaves like the present empty list — it denies nothing for the type AND blocks
# the "*" denial fallback. That cannot exceed the role: the grant axis still gates every
# action independently, so the worst case is the role's own expanded grants.
denied_for(role_def, type) := role_def.denied_actions[type]

denied_for(role_def, type) := role_def.denied_actions["*"] if {
	not type in object.keys(role_def.denied_actions)
}

denied_for(role_def, type) := [] if {
	not type in object.keys(role_def.denied_actions)
	not "*" in object.keys(role_def.denied_actions)
}

# --- the effective set --------------------------------------------------------

# effective_actions(role_def, type): expand the granted category tokens through the table, then
# subtract the denials. Total — a missing/malformed role_def or type yields the EMPTY set (deny),
# and an unknown token contributes nothing (the set comprehension simply finds no expansion).
effective_actions(role_def, type) := actions if {
	expanded := {action |
		some token in tokens_for(role_def, type)
		some action in data.permission_categories[token]
	}
	denied := {action | some action in denied_for(role_def, type)}
	actions := expanded - denied
}
