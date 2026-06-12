package catalog_test

import data.catalog

# --- role-definition-driven (PRIMARY) ---------------------------------------
#
# Phase 6.5: roles grant COARSE categories (READ/WRITE/TAG/GRANT) expanded through
# data.permission_categories; actions carry FINE verbs (view/list/create/update/delete/…).
# Every pre-6.5 behavioral cell below is preserved, re-expressed at the new vocabulary.

viewer_role_def := {
	"code": "catalog-viewer",
	"attributes": {"role_level": 10},
	"permissions": {"catalog": ["READ"], "category": ["READ"], "product": ["READ"]},
}

editor_role_def := {
	"code": "catalog-editor",
	"attributes": {"role_level": 20},
	"permissions": {
		"catalog": ["READ", "WRITE", "TAG"],
		"category": ["READ", "WRITE", "TAG"],
		"product": ["READ", "WRITE", "TAG"],
	},
}

test_viewer_role_def_views if {
	catalog.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_viewer_role_def_cannot_update if {
	not catalog.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_editor_role_def_views if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

test_editor_role_def_updates if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# --- P3: a stale flat token decides NOTHING (the clean cut's ∅-expansion floor) ---

stale_flat_role := {
	"code": "stale-viewer",
	"attributes": {"role_level": 10},
	"permissions": {"catalog": ["read", "write"]},
}

test_stale_flat_token_denies_view if {
	not catalog.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": stale_flat_role,
		"environment": {},
	}
}

test_stale_flat_token_denies_update if {
	not catalog.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": stale_flat_role,
		"environment": {},
	}
}

# --- P6: deny-overrides — WRITE granted, delete denied --------------------------

no_delete_editor := {
	"code": "no-delete-editor",
	"attributes": {"role_level": 20},
	"permissions": {"catalog": ["READ", "WRITE"]},
	"denied_actions": {"catalog": ["delete"]},
}

test_denied_action_update_still_allows if {
	catalog.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": no_delete_editor,
		"environment": {},
	}
}

test_denied_action_delete_denies if {
	not catalog.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "catalog:delete",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": no_delete_editor,
		"environment": {},
	}
}

# --- fallback to subject roles (no role_definition) -------------------------

test_fallback_viewer_views if {
	catalog.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

test_fallback_viewer_cannot_update if {
	not catalog.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

test_fallback_editor_updates if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

# --- P7: the realm fallback's EXACT reach through the table ----------------------
#
# viewer = effective_from_categories({READ}) = {view, list} — nothing more.
# editor = effective_from_categories({READ, WRITE, TAG}) = all seven fine actions except
# assign-roles (GRANT is never realm-granted).

fallback_input(roles, action) := {
	"subject": {"id": "u", "roles": roles},
	"action": action,
	"resource": {"type": "catalog", "id": "p1"},
	"environment": {},
}

test_fallback_viewer_reach_exactly_read if {
	catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:view")
	catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:list")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:create")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:update")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:delete")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:define-tags")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:assign-tags")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:assign-roles")
}

test_fallback_editor_reach_read_write_tag if {
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:view")
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:list")
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:create")
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:update")
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:delete")
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:define-tags")
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:assign-tags")
	not catalog.allow with input as fallback_input(["catalog-editor"], "catalog:assign-roles")
}

# --- default deny -----------------------------------------------------------

test_default_deny_unknown_role if {
	not catalog.allow with input as {
		"subject": {"id": "u3", "roles": ["random-role"]},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

test_default_deny_no_roles_no_role_def if {
	not catalog.allow with input as {
		"subject": {"id": "u3", "roles": []},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "p1"},
		"environment": {},
	}
}

# --- Phase 5.97: tag-based grants (the category.rego cells, mirrored — P1..P5) ----------------
#
# The attribute-rich gate is only as good as the policies it feeds (retro-audit fold-in #3): a
# role's required_tags must gate catalog actions exactly as it gates category actions.

# requires region ANY_OF [emea]; grants catalog READ+WRITE.
regional_catalog_writer := {
	"code": "regional-catalog-writer",
	"attributes": {"role_level": 20},
	"permissions": {"catalog": ["READ", "WRITE"]},
	"required_tags": {"region": ["emea"]},
	"match_mode": "ANY_OF",
}

# requires region:[emea] AND sensitivity:[public, internal] (both must hold)
strict_catalog_writer := {
	"code": "strict-catalog-writer",
	"attributes": {"role_level": 20},
	"permissions": {"catalog": ["READ", "WRITE"]},
	"required_tags": {"region": ["emea"], "sensitivity": ["public", "internal"]},
	"match_mode": "ALL_OF",
}

catalog_tag_input(role_def, tags) := {
	"subject": {"id": "u1", "roles": []},
	"action": "catalog:update",
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
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "c1", "attributes": {"region": "apac"}},
		"environment": {},
	}
}
