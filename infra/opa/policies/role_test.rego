# The assignable truth table (Phase 6.5 — QA P13). Inputs are RAW row snapshots
# ({permissions, denied_actions}) — wildcard lookup happens inside effective_actions.

package role_test

import data.role

verdict(actor, candidate) if {
	role.assignable with input as {
		"actor_role": actor,
		"candidate_role": candidate,
	}
}

# --- subset holds -------------------------------------------------------------

test_subset_assignable if {
	verdict(
		{"permissions": {"catalog": ["READ", "WRITE", "TAG"]}},
		{"permissions": {"catalog": ["READ"]}},
	)
}

test_equal_sets_assignable if {
	verdict(
		{"permissions": {"catalog": ["READ", "WRITE"]}},
		{"permissions": {"catalog": ["READ", "WRITE"]}},
	)
}

test_empty_candidate_assignable if {
	# a candidate granting nothing is vacuously a subset
	verdict({"permissions": {"catalog": ["READ"]}}, {"permissions": {}})
}

# --- subset fails ---------------------------------------------------------------

test_superset_candidate_not_assignable if {
	not verdict(
		{"permissions": {"catalog": ["READ"]}},
		{"permissions": {"catalog": ["READ", "WRITE"]}},
	)
}

test_candidate_reaches_type_actor_lacks if {
	not verdict(
		{"permissions": {"catalog": ["READ", "WRITE", "TAG"]}},
		{"permissions": {"catalog": ["READ"], "product": ["READ"]}},
	)
}

# --- denials narrow the EFFECTIVE sets --------------------------------------------

test_actor_denial_blocks_candidate_grant if {
	# the actor's denial removed delete; the candidate still grants it -> not a subset
	not verdict(
		{
			"permissions": {"catalog": ["READ", "WRITE"]},
			"denied_actions": {"catalog": ["delete"]},
		},
		{"permissions": {"catalog": ["WRITE"]}},
	)
}

test_candidate_own_denial_rescues if {
	# WRITE-minus-delete ⊆ WRITE-minus-delete — the candidate's denial brings it back inside
	verdict(
		{
			"permissions": {"catalog": ["READ", "WRITE"]},
			"denied_actions": {"catalog": ["delete"]},
		},
		{
			"permissions": {"catalog": ["WRITE"]},
			"denied_actions": {"catalog": ["delete"]},
		},
	)
}

# --- wildcard awareness -------------------------------------------------------------

test_concrete_candidate_vs_wildcard_actor if {
	# the system-role shape: the actor's "*" backs up any concrete candidate key
	verdict(
		{"permissions": {"*": ["READ", "WRITE", "TAG"]}},
		{"permissions": {"catalog": ["READ", "WRITE"]}},
	)
}

test_wildcard_candidate_vs_concrete_actor_not_assignable if {
	# a "*" candidate reaches every type; a concrete-keyed actor does not
	not verdict(
		{"permissions": {"catalog": ["READ", "WRITE", "TAG"]}},
		{"permissions": {"*": ["READ"]}},
	)
}

test_wildcard_both_sides if {
	verdict(
		{"permissions": {"*": ["READ", "WRITE", "TAG"]}},
		{"permissions": {"*": ["READ"]}},
	)
}

test_actor_wildcard_denial_applies if {
	# the actor's "*"-scoped denial narrows its effective set on the concrete lookup
	not verdict(
		{
			"permissions": {"*": ["READ", "WRITE"]},
			"denied_actions": {"*": ["delete"]},
		},
		{"permissions": {"catalog": ["WRITE"]}},
	)
}

# --- stale tokens / fail-closed --------------------------------------------------------

test_candidate_stale_token_adds_nothing if {
	# "read" expands to ∅ — the candidate's reach is unchanged by it
	verdict(
		{"permissions": {"catalog": ["READ"]}},
		{"permissions": {"catalog": ["READ", "read"]}},
	)
}

test_stale_only_candidate_assignable if {
	# a candidate of ONLY stale tokens grants nothing -> vacuous subset
	verdict(
		{"permissions": {"catalog": ["READ"]}},
		{"permissions": {"catalog": ["read", "write"]}},
	)
}

test_missing_actor_role_false if {
	not role.assignable with input as {"candidate_role": {"permissions": {"catalog": ["READ"]}}}
}

test_missing_candidate_role_false if {
	not role.assignable with input as {"actor_role": {"permissions": {"catalog": ["READ"]}}}
}

test_malformed_permissions_false if {
	not verdict({"permissions": "oops"}, {"permissions": {"catalog": ["READ"]}})
	not verdict({"permissions": {"catalog": ["READ"]}}, {"permissions": ["READ"]})
}

test_empty_input_false if {
	not role.assignable with input as {}
}

# --- malformed denial in a snapshot (deep review 2026-08-24) ---------------------

# A malformed consulted denial value collapses effective_actions to undefined, so the subset
# walk never runs -> not assignable. The actor side is the amplifier that matters: a corrupt
# actor snapshot must never WIDEN what that actor may hand out (dropping the actor's "*"
# denial would have done exactly that).
test_malformed_denial_in_actor_role_not_assignable if {
	verdict(
		{"permissions": {"catalog": ["READ", "WRITE"]}, "denied_actions": {"*": ["delete"]}},
		{"permissions": {"catalog": ["READ"]}},
	)
	not verdict(
		{
			"permissions": {"catalog": ["READ", "WRITE"]},
			"denied_actions": {"*": ["delete"], "catalog": false},
		},
		{"permissions": {"catalog": ["READ"]}},
	)
}

test_malformed_denial_in_candidate_role_not_assignable if {
	not verdict(
		{"permissions": {"catalog": ["READ", "WRITE"]}},
		{"permissions": {"catalog": ["READ"]}, "denied_actions": {"catalog": false}},
	)
}
