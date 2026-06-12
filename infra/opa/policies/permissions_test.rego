# The expansion algebra (Phase 6.5 — QA P1–P4, P12). Runs against the REAL
# data.permission_categories table (permission_categories.json) — parity with the app-side
# validation table is pinned by the user-mgmt unit suite (U9).

package permissions_test

import data.permissions

# --- P1: per-category expansion ----------------------------------------------

test_read_expands_to_view_list if {
	permissions.effective_actions({"permissions": {"category": ["READ"]}}, "category") == {"view", "list"}
}

test_write_expands_to_create_update_delete if {
	permissions.effective_actions({"permissions": {"category": ["WRITE"]}}, "category") == {"create", "update", "delete"}
}

test_tag_expands_to_define_assign if {
	permissions.effective_actions({"permissions": {"category": ["TAG"]}}, "category") == {"define-tags", "assign-tags"}
}

test_grant_expands_to_assign_roles if {
	permissions.effective_actions({"permissions": {"category": ["GRANT"]}}, "category") == {"assign-roles"}
}

test_categories_union if {
	permissions.effective_actions(
		{"permissions": {"category": ["READ", "WRITE"]}},
		"category",
	) == {"view", "list", "create", "update", "delete"}
}

# --- P2: denial subtraction (after expansion) ----------------------------------

test_denial_subtracts_after_expansion if {
	permissions.effective_actions(
		{
			"permissions": {"category": ["WRITE"]},
			"denied_actions": {"category": ["delete"]},
		},
		"category",
	) == {"create", "update"}
}

test_denial_scoped_to_its_type if {
	# a denial on product does not narrow category
	permissions.effective_actions(
		{
			"permissions": {"category": ["WRITE"], "product": ["WRITE"]},
			"denied_actions": {"product": ["delete"]},
		},
		"category",
	) == {"create", "update", "delete"}
}

# --- P3: stale/unknown tokens expand to nothing (the clean cut's floor) ---------

test_stale_flat_token_expands_to_nothing if {
	permissions.effective_actions({"permissions": {"category": ["read"]}}, "category") == set()
}

test_unknown_token_expands_to_nothing if {
	permissions.effective_actions({"permissions": {"category": ["BOGUS"]}}, "category") == set()
}

test_stale_token_contributes_nothing_alongside_real_one if {
	# the stale "write" adds nothing; the READ category still expands
	permissions.effective_actions(
		{"permissions": {"category": ["READ", "write"]}},
		"category",
	) == {"view", "list"}
}

# --- P4: wildcard fallback ------------------------------------------------------

test_wildcard_fallback_when_type_key_absent if {
	permissions.effective_actions({"permissions": {"*": ["READ"]}}, "category") == {"view", "list"}
}

test_concrete_key_wins_over_wildcard if {
	permissions.effective_actions(
		{"permissions": {"*": ["READ", "WRITE"], "category": ["READ"]}},
		"category",
	) == {"view", "list"}
}

test_empty_concrete_key_does_not_fall_back if {
	# an explicitly EMPTY grant list for the type is a present key — no wildcard widening
	permissions.effective_actions(
		{"permissions": {"*": ["READ"], "category": []}},
		"category",
	) == set()
}

test_wildcard_denial_applies_to_fallback if {
	# the "*" denial narrows the "*" grant on any type lookup
	permissions.effective_actions(
		{
			"permissions": {"*": ["READ", "WRITE"]},
			"denied_actions": {"*": ["delete"]},
		},
		"category",
	) == {"view", "list", "create", "update"}
}

test_concrete_denial_wins_over_wildcard_denial if {
	permissions.effective_actions(
		{
			"permissions": {"category": ["WRITE"]},
			"denied_actions": {"*": ["delete"], "category": ["update"]},
		},
		"category",
	) == {"create", "delete"}
}

# --- P12: edge algebra -----------------------------------------------------------

test_empty_permissions_yield_empty_set if {
	permissions.effective_actions({"permissions": {}}, "category") == set()
}

test_missing_permissions_yield_empty_set if {
	permissions.effective_actions({}, "category") == set()
}

test_denial_of_ungranted_action_is_inert if {
	# authoring rejects this shape, but the policy must not widen on it
	permissions.effective_actions(
		{
			"permissions": {"category": ["READ"]},
			"denied_actions": {"category": ["delete"]},
		},
		"category",
	) == {"view", "list"}
}

test_denials_never_add if {
	permissions.effective_actions(
		{
			"permissions": {},
			"denied_actions": {"category": ["view"]},
		},
		"category",
	) == set()
}

test_denying_everything_granted_yields_empty_set if {
	permissions.effective_actions(
		{
			"permissions": {"category": ["READ"]},
			"denied_actions": {"category": ["view", "list"]},
		},
		"category",
	) == set()
}

# --- effective_from_categories (the realm fallback's helper) ----------------------

test_from_categories_read if {
	permissions.effective_from_categories({"READ"}) == {"view", "list"}
}

test_from_categories_editor_set if {
	permissions.effective_from_categories({"READ", "WRITE", "TAG"}) == {
		"view", "list",
		"create", "update", "delete",
		"define-tags", "assign-tags",
	}
}

test_from_categories_unknown_token_contributes_nothing if {
	permissions.effective_from_categories({"READ", "bogus"}) == {"view", "list"}
}

test_from_categories_empty if {
	permissions.effective_from_categories(set()) == set()
}
