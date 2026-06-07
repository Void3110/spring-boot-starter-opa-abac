package product_test

import data.product

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
	product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:read",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_viewer_role_def_cannot_write if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:write",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_editor_role_def_reads if {
	product.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "product:read",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

test_editor_role_def_writes if {
	product.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "product:write",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# --- fallback to subject roles (no role_definition) -------------------------

test_fallback_viewer_reads if {
	product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:read",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

test_fallback_viewer_cannot_write if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:write",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

test_fallback_editor_writes if {
	product.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "product:write",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

# --- default deny -----------------------------------------------------------

test_default_deny_unknown_role if {
	not product.allow with input as {
		"subject": {"id": "u3", "roles": ["random-role"]},
		"action": "product:read",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

test_default_deny_no_roles_no_role_def if {
	not product.allow with input as {
		"subject": {"id": "u3", "roles": []},
		"action": "product:write",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

# --- Phase 5.5-A: N-level ancestor inheritance + deny-overrides --------------

# A role resolved on the governing root (a Catalog) grants `read` on the catalog type only.
catalog_root_role := {
	"code": "catalog-viewer",
	"attributes": {},
	"permissions": {"catalog": ["read"]},
}

# product inherits from catalog (opt-in, default-off): declared here per-test via `with data`.
product_inherits_catalog := {"product": {"catalog": true}}

deep_product_input(role_def) := {
	"subject": {"id": "u1", "roles": ["catalog-viewer"]},
	"action": "product:read",
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
		"action": "product:read",
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
		"action": "product:read",
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
		"action": "product:read",
		"resource": {"type": "product", "id": "p1", "attributes": {}},
		"role_definition": {"code": "r", "attributes": {}, "permissions": {"product": ["read"]}},
		"environment": {},
	}
		with data.product.inheritable as product_inherits_catalog
}

# An inheritable relation that the role does NOT grant the verb on → deny (ancestor present but no grant).
test_inheritable_but_no_ancestor_grant_denies if {
	not product.allow with input as deep_product_input({
		"code": "r",
		"attributes": {},
		"permissions": {"catalog": ["write"]}, # only write on catalog, not read
	})
		with data.product.inheritable as product_inherits_catalog
}
