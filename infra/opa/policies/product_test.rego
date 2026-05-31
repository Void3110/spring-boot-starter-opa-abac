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
