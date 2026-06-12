package catalog_test

import data.catalog

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
	catalog.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "catalog:read",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_viewer_role_def_cannot_write if {
	not catalog.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "catalog:write",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_editor_role_def_reads if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "catalog:read",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

test_editor_role_def_writes if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "catalog:write",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# --- fallback to subject roles (no role_definition) -------------------------

test_fallback_viewer_reads if {
	catalog.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "catalog:read",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

test_fallback_viewer_cannot_write if {
	not catalog.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "catalog:write",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

test_fallback_editor_writes if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "catalog:write",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

# --- default deny -----------------------------------------------------------

test_default_deny_unknown_role if {
	not catalog.allow with input as {
		"subject": {"id": "u3", "roles": ["random-role"]},
		"action": "catalog:read",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

test_default_deny_no_roles_no_role_def if {
	not catalog.allow with input as {
		"subject": {"id": "u3", "roles": []},
		"action": "catalog:write",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

# --- Phase 5.97: tag-based grants (the category.rego cells, mirrored — P1..P5) ----------------
#
# The attribute-rich gate is only as good as the policies it feeds (retro-audit fold-in #3): a
# role's required_tags must gate catalog actions exactly as it gates category actions.

# requires region ANY_OF [emea]; grants catalog read+write.
regional_catalog_writer := {
	"code": "regional-catalog-writer",
	"attributes": {"role_level": 20},
	"permissions": {"catalog": ["read", "write"]},
	"required_tags": {"region": ["emea"]},
	"match_mode": "ANY_OF",
}

# requires region:[emea] AND sensitivity:[public, internal] (both must hold)
strict_catalog_writer := {
	"code": "strict-catalog-writer",
	"attributes": {"role_level": 20},
	"permissions": {"catalog": ["read", "write"]},
	"required_tags": {"region": ["emea"], "sensitivity": ["public", "internal"]},
	"match_mode": "ALL_OF",
}

catalog_tag_input(role_def, tags) := {
	"subject": {"id": "u1", "roles": []},
	"action": "catalog:write",
	"resource": {"type": "catalog", "id": "c1", "attributes": tags},
	"role_definition": role_def,
	"environment": {},
}

# P1 — ANY_OF hit: the resource's region intersects the required set -> allow.
test_tag_any_of_write_hit if {
	catalog.allow with input as catalog_tag_input(regional_catalog_writer, {"region": "emea"})
}

# P1 — ANY_OF miss: the apac catalog is denied for the emea-gated role.
test_tag_any_of_write_miss if {
	not catalog.allow with input as catalog_tag_input(regional_catalog_writer, {"region": "apac"})
}

# P2 — ALL_OF with both keys satisfied -> allow.
test_tag_all_of_write_hit if {
	catalog.allow with input as catalog_tag_input(
		strict_catalog_writer,
		{"region": ["emea"], "sensitivity": "internal"},
	)
}

# P2 — ALL_OF with one key missing -> deny (universal `every`).
test_tag_all_of_write_partial if {
	not catalog.allow with input as catalog_tag_input(
		strict_catalog_writer,
		{"region": ["emea"]},
	)
}

# P3 — a tag-requiring role against a resource with NO attributes -> deny (fail-closed on
# absence; the conjunct must not be vacuously true when the resource carries nothing).
test_tag_required_no_attributes_denies if {
	not catalog.allow with input as catalog_tag_input(regional_catalog_writer, {})
}

# P4 — a role with NO required_tags is unaffected (vacuous back-compat; every pre-existing
# cell above runs unmodified for the same reason).
test_tag_free_role_unaffected if {
	catalog.allow with input as catalog_tag_input(editor_role_def, {"region": "apac"})
}

# P5 — no role definition at all: the realm fallback decides exactly as before (the conjunct
# sits only on the role-definition grant path).
test_tag_fallback_path_unaffected if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "catalog:write",
		"resource": {"type": "catalog", "id": "c1", "attributes": {"region": "apac"}},
		"environment": {},
	}
}
