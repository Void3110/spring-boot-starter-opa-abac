package product_test

import data.product

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
	product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:view",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_viewer_role_def_cannot_update if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_editor_role_def_views if {
	product.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "product:view",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

test_editor_role_def_updates if {
	product.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# --- P3: a stale flat token decides NOTHING (the clean cut's ∅-expansion floor) ---

stale_flat_role := {
	"code": "stale-writer",
	"attributes": {"role_level": 20},
	"permissions": {"product": ["read", "write"]},
}

test_stale_flat_token_denies if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "product:view",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": stale_flat_role,
		"environment": {},
	}
}

# --- P6: deny-overrides — WRITE granted, delete denied --------------------------

no_delete_writer := {
	"code": "no-delete-writer",
	"attributes": {"role_level": 20},
	"permissions": {"product": ["READ", "WRITE"]},
	"denied_actions": {"product": ["delete"]},
}

test_denied_action_update_still_allows if {
	product.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": no_delete_writer,
		"environment": {},
	}
}

test_denied_action_delete_denies if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "product:delete",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": no_delete_writer,
		"environment": {},
	}
}

# --- fallback to subject roles (no role_definition) -------------------------

test_fallback_viewer_views if {
	product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:view",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

test_fallback_viewer_cannot_update if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

test_fallback_editor_updates if {
	product.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

# --- default deny -----------------------------------------------------------

test_default_deny_unknown_role if {
	not product.allow with input as {
		"subject": {"id": "u3", "roles": ["random-role"]},
		"action": "product:view",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

test_default_deny_no_roles_no_role_def if {
	not product.allow with input as {
		"subject": {"id": "u3", "roles": []},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

# --- Phase 5.5-A: N-level ancestor inheritance + deny-overrides --------------

# A role resolved on the governing root (a Catalog) grants READ on the catalog type only.
catalog_root_role := {
	"code": "catalog-viewer",
	"attributes": {},
	"permissions": {"catalog": ["READ"]},
}

# product inherits from catalog (opt-in, default-off): declared here per-test via `with data`.
product_inherits_catalog := {"product": {"catalog": true}}

deep_product_input(role_def) := {
	"subject": {"id": "u1", "roles": ["catalog-viewer"]},
	"action": "product:view",
	"resource": {
		"type": "product",
		"id": "p1",
		"attributes": {},
		"ancestors": [{"type": "catalog", "id": "c1"}, {"type": "category", "id": "k1"}],
	},
	"role_definition": role_def,
	"environment": {},
}

# INHERITED: a Catalog grant authorizes a Product 3 levels down (opt-in ON).
test_inherited_grant_from_catalog_ancestor if {
	product.allow with input as deep_product_input(catalog_root_role)
		with data.product.inheritable as product_inherits_catalog
}

# OPT-IN OFF: the SAME ancestor grant with EMPTY inheritable data → deny (default-off, never widens).
# (Override the bundled data.product.inheritable with {} to model "this relation is not declared".)
test_opt_in_off_no_inheritance if {
	not product.allow with input as deep_product_input(catalog_root_role)
		with data.product.inheritable as {}
}

# DENY-OVERRIDES: an explicit leaf deny beats an inheritable ancestor grant.
test_deny_overrides_beats_inherited_grant if {
	denied_input := {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:view",
		"resource": {
			"type": "product",
			"id": "p1",
			"attributes": {"abac_deny": true},
			"ancestors": [{"type": "catalog", "id": "c1"}],
		},
		"role_definition": catalog_root_role,
		"environment": {},
	}
	not product.allow with input as denied_input
		with data.product.inheritable as product_inherits_catalog
}

# NO ANCESTORS: behaves exactly as the pre-hierarchy direct-only decision.
# A catalog-only role on a product WITHOUT ancestors → no direct product grant → deny.
test_no_ancestors_is_direct_only_deny if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:view",
		"resource": {"type": "product", "id": "p1", "attributes": {}},
		"role_definition": catalog_root_role,
		"environment": {},
	}
		with data.product.inheritable as product_inherits_catalog
}

# DIRECT grant still works alongside inheritance (a product-typed permission, no ancestors needed).
test_direct_grant_unaffected_by_inheritance if {
	product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:view",
		"resource": {"type": "product", "id": "p1", "attributes": {}},
		"role_definition": {"code": "r", "attributes": {}, "permissions": {"product": ["READ"]}},
		"environment": {},
	}
		with data.product.inheritable as product_inherits_catalog
}

# An inheritable relation whose effective set lacks the verb → deny (WRITE on catalog grants
# create/update/delete on the ancestor — never view).
test_inheritable_but_no_ancestor_grant_denies if {
	not product.allow with input as deep_product_input({
		"code": "r",
		"attributes": {},
		"permissions": {"catalog": ["WRITE"]}, # WRITE expands without view
	})
		with data.product.inheritable as product_inherits_catalog
}

# A DENIAL on the ancestor type narrows the INHERITED grant too.
test_inherited_grant_respects_ancestor_denial if {
	not product.allow with input as deep_product_input({
		"code": "no-view-root",
		"attributes": {},
		"permissions": {"catalog": ["READ"]},
		"denied_actions": {"catalog": ["view"]},
	})
		with data.product.inheritable as product_inherits_catalog
}

# --- Phase 5.97: tag-based grants (the category.rego cells, mirrored — P1..P5) ----------------
#
# The attribute-rich gate is only as good as the policies it feeds (retro-audit fold-in #3): a
# role's required_tags must gate product updates exactly as it gates category updates.

# requires region ANY_OF [emea]; grants product READ+WRITE (the tag-gated writer of the e2e matrix).
regional_writer_any := {
	"code": "regional-writer",
	"attributes": {"role_level": 20},
	"permissions": {"product": ["READ", "WRITE"]},
	"required_tags": {"region": ["emea"]},
	"match_mode": "ANY_OF",
}

# requires region:[emea] AND sensitivity:[public, internal] (both must hold)
strict_writer_all := {
	"code": "strict-writer",
	"attributes": {"role_level": 20},
	"permissions": {"product": ["READ", "WRITE"]},
	"required_tags": {"region": ["emea"], "sensitivity": ["public", "internal"]},
	"match_mode": "ALL_OF",
}

product_tag_input(role_def, tags) := {
	"subject": {"id": "u1", "roles": []},
	"action": "product:update",
	"resource": {"type": "product", "id": "p1", "attributes": tags},
	"role_definition": role_def,
	"environment": {},
}

# P1 — ANY_OF hit: the resource's region intersects the required set -> allow.
test_tag_any_of_write_hit if {
	product.allow with input as product_tag_input(regional_writer_any, {"region": "emea"})
}

# P1 — ANY_OF miss: the apac product is denied for the emea-gated role.
test_tag_any_of_write_miss if {
	not product.allow with input as product_tag_input(regional_writer_any, {"region": "apac"})
}

# P2 — ALL_OF with both keys satisfied -> allow.
test_tag_all_of_write_hit if {
	product.allow with input as product_tag_input(
		strict_writer_all,
		{"region": ["emea"], "sensitivity": "internal"},
	)
}

# P2 — ALL_OF with one key missing -> deny (universal `every`).
test_tag_all_of_write_partial if {
	not product.allow with input as product_tag_input(
		strict_writer_all,
		{"region": ["emea"]},
	)
}

# P3 — a tag-requiring role against a resource with NO attributes -> deny (fail-closed on
# absence; the conjunct must not be vacuously true when the resource carries nothing).
test_tag_required_no_attributes_denies if {
	not product.allow with input as product_tag_input(regional_writer_any, {})
}

# P4 — a role with NO required_tags is unaffected (vacuous back-compat; every pre-existing
# cell above runs unmodified for the same reason).
test_tag_free_role_unaffected if {
	product.allow with input as product_tag_input(editor_role_def, {"region": "apac"})
}

# P5 — no role definition at all: the realm fallback decides exactly as before (the conjunct
# sits only on the role-definition grant path).
test_tag_fallback_path_unaffected if {
	product.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1", "attributes": {"region": "apac"}},
		"environment": {},
	}
}

# The INHERITED path carries the leaf's tag requirement too: an inheritable catalog grant on a
# tag-gated role only authorizes a product whose tags satisfy the requirement.
tag_gated_root_role := {
	"code": "regional-catalog-writer",
	"attributes": {},
	"permissions": {"catalog": ["READ", "WRITE"]},
	"required_tags": {"region": ["emea"]},
	"match_mode": "ANY_OF",
}

inherited_tag_input(tags) := {
	"subject": {"id": "u1", "roles": []},
	"action": "product:update",
	"resource": {
		"type": "product",
		"id": "p1",
		"attributes": tags,
		"ancestors": [{"type": "catalog", "id": "c1"}],
	},
	"role_definition": tag_gated_root_role,
	"environment": {},
}

test_inherited_grant_with_satisfied_leaf_tags if {
	product.allow with input as inherited_tag_input({"region": "emea"})
		with data.product.inheritable as product_inherits_catalog
}

test_inherited_grant_with_mismatched_leaf_tags_denies if {
	not product.allow with input as inherited_tag_input({"region": "apac"})
		with data.product.inheritable as product_inherits_catalog
}
