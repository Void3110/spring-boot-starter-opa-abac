package category_test

import data.category

# --- role-definition-driven (PRIMARY) ---------------------------------------

viewer_role_def := {
	"code": "catalog-viewer",
	"attributes": {"role_level": 10},
	"permissions": {"catalog": ["read"], "category": ["read"], "product": ["read"]},
}

editor_role_def := {
	"code": "catalog-editor",
	"attributes": {"role_level": 20},
	"permissions": {
		"catalog": ["read", "write"],
		"category": ["read", "write"],
		"product": ["read", "write"],
	},
}

test_viewer_role_def_reads if {
	category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:read",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_viewer_role_def_cannot_write if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:write",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_editor_role_def_reads if {
	category.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "category:read",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

test_editor_role_def_writes if {
	category.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "category:write",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# --- fallback to subject roles (no role_definition) -------------------------

test_fallback_viewer_reads if {
	category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:read",
		"resource": {"type": "category", "id": "p1"},
		"environment": {},
	}
}

test_fallback_viewer_cannot_write if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:write",
		"resource": {"type": "category", "id": "p1"},
		"environment": {},
	}
}

test_fallback_editor_writes if {
	category.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "category:write",
		"resource": {"type": "category", "id": "p1"},
		"environment": {},
	}
}

# --- default deny -----------------------------------------------------------

test_default_deny_unknown_role if {
	not category.allow with input as {
		"subject": {"id": "u3", "roles": ["random-role"]},
		"action": "category:read",
		"resource": {"type": "category", "id": "p1"},
		"environment": {},
	}
}

test_default_deny_no_roles_no_role_def if {
	not category.allow with input as {
		"subject": {"id": "u3", "roles": []},
		"action": "category:write",
		"resource": {"type": "category", "id": "p1"},
		"environment": {},
	}
}

# --- tag-based grants (Phase 4.5: tags_satisfied — T1..T10) ------------------
#
# A role that requires tags. The decisive contrast: identical permissions, the grant flips
# purely on whether the resource's tags satisfy the requirement, matched in Rego.

# requires region ANY_OF [emea]
regional_reader_any := {
	"code": "regional-reader",
	"attributes": {"role_level": 15},
	"permissions": {"category": ["read"]},
	"required_tags": {"region": ["emea"]},
	"match_mode": "ANY_OF",
}

# requires region:[emea] AND sensitivity:[public, internal] (both must hold)
strict_reader_all := {
	"code": "strict-reader",
	"attributes": {"role_level": 15},
	"permissions": {"category": ["read"]},
	"required_tags": {"region": ["emea"], "sensitivity": ["public", "internal"]},
	"match_mode": "ALL_OF",
}

# requires region ANY_OF [emea, apac] (either acceptable)
multi_region_any := {
	"code": "multi-region",
	"attributes": {"role_level": 15},
	"permissions": {"category": ["read"]},
	"required_tags": {"region": ["emea"], "sensitivity": ["public"]},
	"match_mode": "ANY_OF",
}

tag_input(role_def, tags) := {
	"subject": {"id": "u1", "roles": []},
	"action": "category:read",
	"resource": {"type": "category", "id": "p1", "attributes": tags},
	"role_definition": role_def,
	"environment": {},
}

# T1 — ANY_OF hit: the resource's region intersects the required set -> allow.
test_any_of_hit if {
	category.allow with input as tag_input(regional_reader_any, {"region": ["emea", "amer"]})
}

# T2 — ANY_OF miss: no required key matches -> deny.
test_any_of_miss if {
	not category.allow with input as tag_input(regional_reader_any, {"region": ["apac"]})
}

# T3 — ALL_OF hit: every required key matches -> allow.
test_all_of_hit if {
	category.allow with input as tag_input(
		strict_reader_all,
		{"region": ["emea"], "sensitivity": "internal"},
	)
}

# T4 — ALL_OF partial: one key matches, another does not -> deny (universal `every`).
test_all_of_partial if {
	not category.allow with input as tag_input(
		strict_reader_all,
		{"region": ["emea"], "sensitivity": "confidential"},
	)
}

# T5 — multi-value intersection: a scalar resource tag in the acceptable set -> satisfied.
test_multi_value_intersection if {
	category.allow with input as tag_input(regional_reader_any, {"region": "emea"})
}

# T6 — no required tags (vacuous): a plain role behaves exactly as Phase 4 (allow on read).
test_vacuous_no_required_tags if {
	category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:read",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": ["apac"]}},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

# T7 — permission ok but tags fail -> deny (both checks required).
test_permission_ok_tags_fail if {
	not category.allow with input as tag_input(regional_reader_any, {"region": ["apac"]})
}

# T8 — tags ok but permission absent (write not granted) -> deny.
test_tags_ok_permission_fail if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:write",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": ["emea"]}},
		"role_definition": regional_reader_any,
		"environment": {},
	}
}

# T9 — malformed required_tags (present, but match_mode unknown) -> fail-closed deny.
test_malformed_required_tags_denies if {
	not category.allow with input as tag_input(
		{
			"code": "broken",
			"permissions": {"category": ["read"]},
			"required_tags": {"region": ["emea"]},
			"match_mode": "WHATEVER",
		},
		{"region": ["emea"]},
	)
}

# T9b — required_tags present but match_mode missing entirely -> fail-closed deny.
test_required_tags_without_mode_denies if {
	not category.allow with input as tag_input(
		{
			"code": "broken2",
			"permissions": {"category": ["read"]},
			"required_tags": {"region": ["emea"]},
		},
		{"region": ["emea"]},
	)
}

# T10 — default deny still holds with required tags + a missing resource tag.
test_default_deny_missing_resource_tag if {
	not category.allow with input as tag_input(regional_reader_any, {"sensitivity": "public"})
}

# ALL_OF with both keys satisfied (the positive of multi_region_any, region OR sensitivity).
test_any_of_second_key_hits if {
	# region miss (apac) but sensitivity public hits -> ANY_OF allow.
	category.allow with input as tag_input(
		multi_region_any,
		{"region": ["apac"], "sensitivity": "public"},
	)
}

# --- Phase 5: filter entrypoint (list filtering) ----------------------------
#
# `filter` is concrete-evaluated here for coverage (the residual / partial-eval shape is asserted by the
# Java HttpOpaClientCompileTest + the e2e matrix). The decisive property: filter is ROLE-DEFINITION-ONLY
# (no subject-roles fallback) so a missing role definition fails CLOSED, and its membership tag match
# matches a scalar OR an array tag — agreeing with the single-decision `allow`.

# An unrestricted role (no required tags) -> filter true for any readable category.
test_filter_unrestricted_reads if {
	category.filter with input as {
		"action": "category:read",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

# A tag-gated role + a matching SCALAR tag -> filter true.
test_filter_tag_gated_scalar_match if {
	category.filter with input as tag_input(regional_reader_any, {"region": "emea"})
}

# A tag-gated role + a matching ARRAY tag -> filter true (membership matches the array element).
test_filter_tag_gated_array_match if {
	category.filter with input as tag_input(regional_reader_any, {"region": ["emea", "amer"]})
}

# A tag-gated role + a non-matching tag -> filter false.
test_filter_tag_gated_miss if {
	not category.filter with input as tag_input(regional_reader_any, {"region": ["apac"]})
}

# filter AGREES with allow for both the scalar and the array case (the consistency property).
test_filter_agrees_with_allow_scalar if {
	req := tag_input(regional_reader_any, {"region": "emea"})
	category.filter with input as req
	category.allow with input as req
}

test_filter_agrees_with_allow_array if {
	req := tag_input(regional_reader_any, {"region": ["emea", "amer"]})
	category.filter with input as req
	category.allow with input as req
}

# U27 — the fail-open-leak guard: NO role_definition -> filter false (would be DENY_ALL on partial eval).
# `allow` would grant this read via its subject-roles fallback, but `filter` must NOT — a list with no
# role definition is empty, never the whole table.
test_filter_no_role_definition_denies if {
	not category.filter with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:read",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"environment": {},
	}
}

# Contrast: `allow` DOES grant the same no-role-def read (the fallback) — proving filter dropped it.
test_allow_grants_no_role_def_read_that_filter_denies if {
	category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:read",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"environment": {},
	}
}

# filter requires the read permission too: a role without category:read -> filter false.
test_filter_requires_read_permission if {
	not category.filter with input as {
		"action": "category:read",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": {"code": "x", "permissions": {"catalog": ["read"]}},
		"environment": {},
	}
}

# --- Phase 5: bulk entrypoint (batch / allowlist primitive) ------------------

# bulk returns a positional list of allow-decisions over input.items.
test_bulk_returns_positional_decisions if {
	result := category.bulk with input as {"items": [
		{
			"subject": {"id": "u", "roles": []},
			"action": "category:read",
			"resource": {"type": "category", "id": "a", "attributes": {}},
			"role_definition": viewer_role_def,
			"environment": {},
		},
		{
			"subject": {"id": "u", "roles": []},
			"action": "category:write",
			"resource": {"type": "category", "id": "b", "attributes": {}},
			"role_definition": viewer_role_def,
			"environment": {},
		},
	]}
	result == [true, false]
}

# bulk over an empty item list -> empty decision list.
test_bulk_empty if {
	result := category.bulk with input as {"items": []}
	result == []
}

# --- Phase 5.5-A: N-level ancestor inheritance + deny-overrides --------------

# A role resolved on the governing root (a Catalog) grants `read` on the catalog type only.
cat_root_role := {
	"code": "catalog-viewer",
	"attributes": {},
	"permissions": {"catalog": ["read"]},
}

# category inherits from catalog (opt-in, default-off): supplied per-test via `with data`.
category_inherits_catalog := {"category": {"catalog": true}}

deep_category_input(role_def, attrs) := {
	"subject": {"id": "u1", "roles": ["catalog-viewer"]},
	"action": "category:read",
	"resource": {
		"type": "category",
		"id": "k1",
		"attributes": attrs,
		"ancestors": [{"type": "catalog", "id": "c1"}],
	},
	"role_definition": role_def,
	"environment": {},
}

# INHERITED: a Catalog grant authorizes a Category under it (opt-in ON), no leaf tag requirement.
test_inherited_grant_from_catalog if {
	category.allow with input as deep_category_input(cat_root_role, {})
		with data.category.inheritable as category_inherits_catalog
}

# OPT-IN OFF: same ancestor grant, EMPTY inheritable data → deny (override the bundled data with {}).
test_opt_in_off_no_inheritance if {
	not category.allow with input as deep_category_input(cat_root_role, {})
		with data.category.inheritable as {}
}

# DENY-OVERRIDES: a leaf deny beats the inherited grant.
test_deny_overrides_beats_inherited if {
	not category.allow with input as deep_category_input(cat_root_role, {"abac_deny": true})
		with data.category.inheritable as category_inherits_catalog
}

# NO ANCESTORS: a catalog-only role on a category with no ancestors → direct-only → deny.
test_no_ancestors_direct_only_deny if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:read",
		"resource": {"type": "category", "id": "k1", "attributes": {}},
		"role_definition": cat_root_role,
		"environment": {},
	}
		with data.category.inheritable as category_inherits_catalog
}

# TAG MATCH ON THE INHERITED PATH: a tag-gated root role only inherits where the LEAF's tags satisfy it.
cat_root_role_tagged := {
	"code": "regional-catalog-reader",
	"attributes": {},
	"permissions": {"catalog": ["read"]},
	"required_tags": {"region": ["emea"]},
	"match_mode": "ANY_OF",
}

# leaf tagged region=emea → inherited grant holds (tag satisfied).
test_inherited_grant_respects_leaf_tags_match if {
	category.allow with input as deep_category_input(cat_root_role_tagged, {"region": "emea"})
		with data.category.inheritable as category_inherits_catalog
}

# leaf tagged region=apac → inherited grant denied (tag NOT satisfied), even though the ancestor grants.
test_inherited_grant_respects_leaf_tags_mismatch if {
	not category.allow with input as deep_category_input(cat_root_role_tagged, {"region": "apac"})
		with data.category.inheritable as category_inherits_catalog
}
