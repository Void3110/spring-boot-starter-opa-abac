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
