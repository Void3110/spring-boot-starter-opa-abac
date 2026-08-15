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

# --- Slice B4: realm-role fallback REMOVED (ADR 0018) -----------------------
#
# A product lives under a governed catalog, so the resolved role (team membership) applies at every
# level; the blanket realm fallback only leaked. With no role_definition, a bare realm role is now
# denied at EVERY verb — closing the deep-link leak (R7).

# R7 — bare catalog-viewer (no role-def) can no longer view a product.
test_fallback_viewer_view_now_denied if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:view",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

# R7 — bare catalog-editor (no role-def) can no longer update a product (the leak we closed).
test_fallback_editor_update_now_denied if {
	not product.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1"},
		"environment": {},
	}
}

# The resolved-role path is UNCHANGED: a role-def granting WRITE still updates.
test_resolved_role_updates_unchanged if {
	product.allow with input as {
		"subject": {"id": "u2", "roles": []},
		"action": "product:update",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# --- Slice B4: the coarse CREATE + LIST gates (type-level, role resolved on the parent catalog) ----
#
# product:create / product:list are type-level. The gate resolves the role on the PARENT catalog (the
# governing root, via the @OpaPreAuthorize roleResource override); a role granting the verb on the
# inheritable catalog ancestor opens the gate. A non-member (no role-def) is denied.

product_type_input(action, role_def) := {
	"subject": {"id": "u", "roles": ["catalog-editor"]},
	"action": action,
	"resource": {"type": "product"}, # type-level — no id
	"role_definition": role_def,
	"environment": {},
}

# A catalog-resolved WRITE role opens product:create; a READ role opens product:list but not create.
test_product_create_inheritable_opens if {
	product.allow with input as product_type_input("product:create", editor_role_def)
		with data.product.inheritable as {"product": {"catalog": true}}
}

test_product_list_inheritable_opens if {
	product.allow with input as product_type_input("product:list", viewer_role_def)
		with data.product.inheritable as {"product": {"catalog": true}}
}

test_product_create_read_only_denied if {
	not product.allow with input as product_type_input("product:create", viewer_role_def)
		with data.product.inheritable as {"product": {"catalog": true}}
}

# A non-member (NO role-def) is denied product:create AND product:list.
test_product_type_level_no_role_def_denied if {
	not product.allow with input as {
		"subject": {"id": "u", "roles": ["catalog-editor"]},
		"action": "product:create",
		"resource": {"type": "product"},
		"environment": {},
	}
		with data.product.inheritable as {"product": {"catalog": true}}

	not product.allow with input as {
		"subject": {"id": "u", "roles": ["catalog-editor"]},
		"action": "product:list",
		"resource": {"type": "product"},
		"environment": {},
	}
		with data.product.inheritable as {"product": {"catalog": true}}
}

# REAL WIRE SHAPE — explicit `"id": null` (regression-protection for the null-safe
# is_type_level_request clause). AbacContext.Resource emits id:null for a type-level decision (no
# @JsonInclude(NON_NULL) on the field), NOT an omitted key. The helper above uses the omitted shape
# (exercising only `not input.resource.id`); these pin the `input.resource.id == null` clause. Drop
# that clause and the omitted-shape tests still pass but these FAIL — and production (always id:null)
# would silently deny every member's product:create/list. See product.rego is_type_level_request.
product_type_input_null_id(action, role_def) := {
	"subject": {"id": "u", "roles": ["catalog-editor"]},
	"action": action,
	"resource": {"type": "product", "id": null, "attributes": {}}, # type-level — explicit null id
	"role_definition": role_def,
	"environment": {},
}

test_product_create_null_id_inheritable_opens if {
	product.allow with input as product_type_input_null_id("product:create", editor_role_def)
		with data.product.inheritable as {"product": {"catalog": true}}
}

test_product_list_null_id_inheritable_opens if {
	product.allow with input as product_type_input_null_id("product:list", viewer_role_def)
		with data.product.inheritable as {"product": {"catalog": true}}
}

# A non-member is denied on the explicit-null wire shape too (isolation holds).
test_product_create_null_id_no_role_def_denied if {
	not product.allow with input as {
		"subject": {"id": "u", "roles": ["catalog-editor"]},
		"action": "product:create",
		"resource": {"type": "product", "id": null, "attributes": {}},
		"environment": {},
	}
		with data.product.inheritable as {"product": {"catalog": true}}
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
	"attributes": {"provenance": "membership"}, # ADR 0031 — membership-derived, so inheritance applies
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
# Deliberately carries NO provenance stamp: ADR 0031 confines only the INHERITANCE path, so a role
# naming the type explicitly reaches it with no stamp at all.
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
		"attributes": {"provenance": "membership"},
		"permissions": {"catalog": ["WRITE"]}, # WRITE expands without view
	})
		with data.product.inheritable as product_inherits_catalog
}

# A DENIAL on the ancestor type narrows the INHERITED grant too.
test_inherited_grant_respects_ancestor_denial if {
	not product.allow with input as deep_product_input({
		"code": "no-view-root",
		"attributes": {"provenance": "membership"},
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

# P5 — the tag conjunct sits only on the role-definition grant path. Slice B4: bare catalog-editor
# (no role-def) can no longer `update` (fallback removed for all verbs) regardless of tags.
test_tag_fallback_path_update_now_denied if {
	not product.allow with input as {
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
	"attributes": {"provenance": "membership"}, # ADR 0031 — membership-derived, so inheritance applies
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

# --- Phase 5 batch primitive (extended to product for Phase 6 action enrichment) ---

# bulk returns a positional list of allow-decisions over input.items (the affordance refold source).
test_bulk_returns_positional_decisions if {
	result := product.bulk with input as {"items": [
		{
			"subject": {"id": "u", "roles": []},
			"action": "product:view",
			"resource": {"type": "product", "id": "a", "attributes": {}},
			"role_definition": viewer_role_def,
			"environment": {},
		},
		{
			"subject": {"id": "u", "roles": []},
			"action": "product:update",
			"resource": {"type": "product", "id": "a", "attributes": {}},
			"role_definition": viewer_role_def,
			"environment": {},
		},
	]}
	result == [true, false]
}

# bulk over an empty item list -> empty decision list.
test_bulk_empty if {
	result := product.bulk with input as {"items": []}
	result == []
}

# --- Taggable products: the assign-tags verb ---------------------------------
#
# Products carry tags now, so product:assign-tags became a live verb — dispatched from the PUT by
# TagDecisionGate (instance-level) and asked type-level on a tag-carrying create. TAG grants it
# through the shared category expansion; READ alone never does.

test_assign_tags_tag_role_allows if {
	product.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "product:assign-tags",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

test_assign_tags_read_only_denied if {
	not product.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "product:assign-tags",
		"resource": {"type": "product", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

# Tag-on-create: the TYPE-LEVEL assign-tags decision, role resolved on the parent catalog (the
# verb-agnostic coarse gate — same clause create/list ride).
test_assign_tags_type_level_inheritable_opens if {
	product.allow with input as product_type_input("product:assign-tags", editor_role_def)
		with data.product.inheritable as {"product": {"catalog": true}}
}

test_assign_tags_type_level_read_only_denied if {
	not product.allow with input as product_type_input("product:assign-tags", viewer_role_def)
		with data.product.inheritable as {"product": {"catalog": true}}
}

test_assign_tags_type_level_no_role_def_denied if {
	not product.allow with input as {
		"subject": {"id": "u", "roles": ["catalog-editor"]},
		"action": "product:assign-tags",
		"resource": {"type": "product", "id": null, "attributes": {}},
		"environment": {},
	}
		with data.product.inheritable as {"product": {"catalog": true}}
}

# --- Taggable products: filter entrypoint (list filtering) -------------------
#
# The category.rego filter block ported when products became taggable; the decisive properties are
# the same (see category_test.rego): ROLE-DEFINITION-ONLY (missing role-def fails CLOSED), the
# category expansion consumed INLINE, "list" required in the expanded-minus-denied set, and the
# membership tag match agreeing with the single-decision `allow` for scalar AND array tags.

product_regional_reader := {
	"code": "regional-reader",
	"attributes": {"role_level": 10},
	"permissions": {"product": ["READ"]},
	"required_tags": {"region": ["emea", "amer"]},
	"match_mode": "ANY_OF",
}

product_list_tag_input(role_def, tags) := {
	"subject": {"id": "u1", "roles": []},
	"action": "product:list",
	"resource": {"type": "product", "id": "p1", "attributes": tags},
	"role_definition": role_def,
	"environment": {},
}

# An unrestricted role (no required tags) -> filter true for any listable product.
test_filter_unrestricted_lists if {
	product.filter with input as product_list_tag_input(viewer_role_def, {"region": "emea"})
}

# A tag-gated role + a matching SCALAR tag -> filter true.
test_filter_tag_gated_scalar_match if {
	product.filter with input as product_list_tag_input(product_regional_reader, {"region": "emea"})
}

# A tag-gated role + a matching ARRAY tag -> filter true (membership matches the array element).
test_filter_tag_gated_array_match if {
	product.filter with input as product_list_tag_input(product_regional_reader, {"region": ["emea", "amer"]})
}

# A tag-gated role + a non-matching tag -> filter false.
test_filter_tag_gated_miss if {
	not product.filter with input as product_list_tag_input(product_regional_reader, {"region": ["apac"]})
}

# filter AGREES with allow for both the scalar and the array case (the consistency property).
test_filter_agrees_with_allow_scalar if {
	req := product_list_tag_input(product_regional_reader, {"region": "emea"})
	product.filter with input as req
	product.allow with input as req
}

test_filter_agrees_with_allow_array if {
	req := product_list_tag_input(product_regional_reader, {"region": ["emea", "amer"]})
	product.filter with input as req
	product.allow with input as req
}

# The fail-open-leak guard: NO role_definition -> filter false (DENY_ALL on partial eval) — a list
# with no role definition is empty, never the whole table. `allow` agrees (B4: membership is the
# sole access path).
test_filter_no_role_definition_denies if {
	req := {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "product:list",
		"resource": {"type": "product", "id": "p1", "attributes": {"region": "emea"}},
		"environment": {},
	}
	not product.filter with input as req
	not product.allow with input as req
}

# filter requires "list" in the EFFECTIVE set: a TAG-only role (no READ) -> filter false.
test_filter_tag_only_role_denies if {
	not product.filter with input as {
		"action": "product:list",
		"resource": {"type": "product", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": {"code": "x", "permissions": {"product": ["TAG"]}},
		"environment": {},
	}
}

# A grant on a DIFFERENT type does not open this type's list.
test_filter_requires_grant_on_this_type if {
	not product.filter with input as {
		"action": "product:list",
		"resource": {"type": "product", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": {"code": "x", "permissions": {"catalog": ["READ"]}},
		"environment": {},
	}
}

# A denial of "list" closes the filter even though READ grants it (deny-overrides at the
# list boundary).
test_filter_list_denied_closes if {
	not product.filter with input as {
		"action": "product:list",
		"resource": {"type": "product", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": {
			"code": "x",
			"permissions": {"product": ["READ"]},
			"denied_actions": {"product": ["list"]},
		},
		"environment": {},
	}
}

# A stale flat token never opens the filter (∅-expansion at the list boundary).
test_filter_stale_flat_token_denies if {
	not product.filter with input as {
		"action": "product:list",
		"resource": {"type": "product", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": stale_flat_role,
		"environment": {},
	}
}

# --- ADR 0031: ancestor inheritance is confined to membership-derived roles (U36) ------------------
#
# The category sibling carries the full case set (U35, U37–U40); products need the same confinement
# proven independently, because BOTH leaf policies declare `catalog` inheritable and a fix applied to
# only one would leave the other leaking. This probe returned TRUE before the conjunct.

supervisor_role := {
	"code": "supervisor-readonly",
	"attributes": {"provenance": "supervised"},
	"permissions": {"catalog": ["READ"]},
}

# U36 — the supervisor role + a product carrying its catalog ancestor → no inherited grant.
test_supervised_role_cannot_inherit_product_view if {
	not product.allow with input as deep_product_input(supervisor_role)
		with data.product.inheritable as product_inherits_catalog
}

# The membership constituency keeps it (the product-side mirror of U38).
test_membership_role_keeps_product_inheritance if {
	product.allow with input as deep_product_input({
		"code": "catalog-owner",
		"attributes": {"provenance": "membership"},
		"permissions": {"catalog": ["READ", "WRITE", "TAG", "GRANT"]},
	})
		with data.product.inheritable as product_inherits_catalog
}

# Absence is closed on the product side too.
test_absent_provenance_grants_no_product_inheritance if {
	not product.allow with input as deep_product_input({"code": "no-attrs", "permissions": {"catalog": ["READ"]}})
		with data.product.inheritable as product_inherits_catalog
}

# The COARSE type-level gate (`product:list`, no resource id) resolves through
# `list_inheritable_grant`, a separate clause from the fine-verb path above — so its conjunct needs
# its own probes: mutation testing showed deleting it (or the whole clause) left this file green.
product_list_gate_input(role_def) := {
	"subject": {"id": "u1", "roles": []},
	"action": "product:list",
	"resource": {"type": "product"},
	"role_definition": role_def,
	"environment": {},
}

# U37 (product mirror) — the type-level list gate is confined to membership-derived roles.
test_supervised_role_cannot_open_the_type_level_product_list_gate if {
	not product.allow with input as product_list_gate_input(supervisor_role)
		with data.product.inheritable as product_inherits_catalog
}

# The membership constituency keeps the type-level gate. `catalog_root_role` has no `product` key,
# so ONLY `list_inheritable_grant` can open this — deleting the clause outright fails here.
test_membership_role_opens_the_type_level_product_list_gate if {
	product.allow with input as product_list_gate_input(catalog_root_role)
		with data.product.inheritable as product_inherits_catalog
}

# --- PRODUCTION TIER (ADR 0030 §3–4, ADR 0032) — U8, U9, U10 ---------------------------------------
#
# The product mirror of category_test's tier block. Same widened supervisor role, same four states,
# same member control — per-type policies drift, so each file carries its own proof rather than
# trusting the sibling's (the sibling-drift lesson).
tiered_supervisor_role := {
	"code": "supervisor-readonly",
	"attributes": {"provenance": "supervised"},
	"permissions": {
		"catalog": ["READ"],
		"category": ["READ"],
		"product": ["READ"],
	},
}

tiered_member_role := {
	"code": "catalog-owner",
	"attributes": {"provenance": "membership"},
	"permissions": {
		"catalog": ["READ"],
		"category": ["READ"],
		"product": ["READ"],
	},
}

unstamped_role := {
	"code": "product-editor",
	"permissions": {"product": ["READ"]},
}

# The instance shape: a product carrying its full chain, plus a root_attributes STATE patched over
# the resource (so ABSENT is a missing key, never a null).
tier_input(role_def, root_state) := {
	"subject": {"id": "u1", "roles": []},
	"action": "product:view",
	"resource": object.union(
		{
			"type": "product",
			"id": "p1",
			"attributes": {},
			"ancestors": [{"type": "catalog", "id": "c1"}, {"type": "category", "id": "k1"}],
		},
		root_state,
	),
	"role_definition": role_def,
	"environment": {},
}

tier_gate_input(role_def, root_state) := {
	"subject": {"id": "u1", "roles": []},
	"action": "product:list",
	"resource": object.union({"type": "product"}, root_state),
	"role_definition": role_def,
	"environment": {},
}

absent_root := {}

untagged_root := {"root_attributes": {}}

staging_root := {"root_attributes": {"env": "staging"}}

production_root := {"root_attributes": {"env": "production"}}

# U8 — the instance shape, all four tier states.
test_tier_instance_absent_root_denies if {
	not product.allow with input as tier_input(tiered_supervisor_role, absent_root)
}

test_tier_instance_untagged_root_allows if {
	product.allow with input as tier_input(tiered_supervisor_role, untagged_root)
}

test_tier_instance_staging_root_allows if {
	product.allow with input as tier_input(tiered_supervisor_role, staging_root)
}

test_tier_instance_production_root_denies if {
	not product.allow with input as tier_input(tiered_supervisor_role, production_root)
}

# U9 — the same four states through the coarse type-level list gate.
test_tier_gate_absent_root_denies if {
	not product.allow with input as tier_gate_input(tiered_supervisor_role, absent_root)
}

test_tier_gate_untagged_root_allows if {
	product.allow with input as tier_gate_input(tiered_supervisor_role, untagged_root)
}

test_tier_gate_staging_root_allows if {
	product.allow with input as tier_gate_input(tiered_supervisor_role, staging_root)
}

test_tier_gate_production_root_denies if {
	not product.allow with input as tier_gate_input(tiered_supervisor_role, production_root)
}

# U10 — members structurally unaffected, in THIS file too (ADR 0030 §2).
test_member_unaffected_by_production_tier if {
	product.allow with input as tier_input(tiered_member_role, production_root)
	product.allow with input as tier_gate_input(tiered_member_role, production_root)
}

test_member_unaffected_by_absent_root_attributes if {
	product.allow with input as tier_input(tiered_member_role, absent_root)
	product.allow with input as tier_gate_input(tiered_member_role, absent_root)
}

test_unstamped_role_unaffected_by_the_tier if {
	product.allow with input as tier_input(unstamped_role, production_root)
	product.allow with input as tier_input(unstamped_role, absent_root)
}

# The supervised READ-only ceiling holds under the tier here too.
test_tier_does_not_widen_the_read_only_ceiling if {
	not product.allow with input as object.union(
		tier_input(tiered_supervisor_role, staging_root),
		{"action": "product:update"},
	)
}

# THE SHAPE TRAP's cardinality twin: an array-shaped env must deny exactly as the scalar does — a
# bare scalar `==` failed OPEN on ["production"] (neither deny clause fired). A non-production array
# stays open: the normalization must not over-close either.
array_production_root := {"root_attributes": {"env": ["production", "staging"]}}

array_non_production_root := {"root_attributes": {"env": ["staging", "dev"]}}

test_tier_instance_array_production_root_denies if {
	not product.allow with input as tier_input(tiered_supervisor_role, array_production_root)
}

test_tier_gate_array_production_root_denies if {
	not product.allow with input as tier_gate_input(tiered_supervisor_role, array_production_root)
}

test_tier_array_non_production_root_allows if {
	product.allow with input as tier_input(tiered_supervisor_role, array_non_production_root)
}

# The tier decision lands at the coarse gate and NEVER in the list residual: `filter` does not consult
# `denied`, so a root_attributes predicate can never reach the SQL. Asserted on the shape a supervised
# list actually compiles with — production root, which the gate above denies — so the two are visibly
# decided in different places.
test_tier_never_enters_the_filter_residual if {
	product.filter with input as tier_gate_input(tiered_supervisor_role, production_root)
}

# --- STEP-UP ELEVATION (ADR 0030 §5–7 + Amendments 1/2/4) — U1–U11 ----------------------------
#
# Every window case runs on a PINNED clock (`with time.now_ns as …`), so the boundary is arithmetic
# rather than wall-clock luck. `data.step_up` comes from `step_up.json` unless a case overrides it —
# and one case deliberately does, to prove the reason's `max_age` is READ from data rather than
# restated anywhere.

stepup_now_s := 1786000000

stepup_now_ns := 1786000000000000000

# The tier fixtures' subject, patched with a claims state. The tier fixtures themselves carry NO
# `subject.attributes` at all, which is why every pre-existing production-deny case above stays green:
# no claims -> `elevated` undefined -> the deny holds.
elev_input(role_def, root_state, attrs) := object.union(
	tier_input(role_def, root_state),
	{"subject": {"id": "u1", "roles": [], "attributes": attrs}},
)

elev_gate_input(role_def, root_state, attrs) := object.union(
	tier_gate_input(role_def, root_state),
	{"subject": {"id": "u1", "roles": [], "attributes": attrs}},
)

# max_age 300 + skew 30 = a 330-second window.
fresh_aal2 := {"acr": "aal2", "auth_time": stepup_now_s - 10}

stale_aal2 := {"acr": "aal2", "auth_time": stepup_now_s - 3600}

boundary_aal2 := {"acr": "aal2", "auth_time": stepup_now_s - 330}

past_boundary_aal2 := {"acr": "aal2", "auth_time": stepup_now_s - 331}

# U1 — no `acr` claim at all: the LoA lookup is undefined, so `elevated` is, so the deny holds.
test_elevation_undefined_without_acr if {
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, {"auth_time": stepup_now_s - 10})
		with time.now_ns as stepup_now_ns
}

# U2 — `acr: aal2` but no `auth_time`: the arithmetic is undefined, so freshness is unproven.
test_elevation_undefined_without_auth_time if {
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, {"acr": "aal2"})
		with time.now_ns as stepup_now_ns
}

# U3 — an `acr` the LoA map does not name is not a level, and a mapped level BELOW 2 is not enough.
test_elevation_undefined_on_unmapped_acr if {
	not product.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "gold", "auth_time": stepup_now_s - 10},
	)
		with time.now_ns as stepup_now_ns
}

test_elevation_denied_on_acr_below_level_two if {
	not product.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal1", "auth_time": stepup_now_s - 10},
	)
		with time.now_ns as stepup_now_ns
}

# The same fail-closed floor for a TYPE-COERCED auth_time — the trap a `to_number` "fix" would open.
test_elevation_undefined_on_non_numeric_auth_time if {
	not product.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal2", "auth_time": "1786000000"},
	)
		with time.now_ns as stepup_now_ns
}

# U4 — THE HEADLINE: a fresh aal2 opens the production tier, on the instance shape AND the coarse
# type-level gate. This is the ONLY clause elevation narrows.
test_fresh_aal2_opens_production_instance if {
	product.allow with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

test_fresh_aal2_opens_production_gate if {
	product.allow with input as elev_gate_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

# …and on the array-shaped env too — the cardinality twin must not become an elevation hole.
test_fresh_aal2_opens_array_shaped_production if {
	product.allow with input as elev_input(tiered_supervisor_role, array_production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

# U5 — a stale `auth_time` denies: the elevation expires on its own, and a refresh cannot launder it
# (the refreshed token carries the SAME auth_time — measured on the rig, ADR 0030 §Context).
test_stale_auth_time_denies if {
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, stale_aal2)
		with time.now_ns as stepup_now_ns
}

# U6 — the window boundary is inclusive: exactly max_age + skew allows, one second past denies.
test_window_boundary_is_inclusive if {
	product.allow with input as elev_input(tiered_supervisor_role, production_root, boundary_aal2)
		with time.now_ns as stepup_now_ns
}

test_one_second_past_the_boundary_denies if {
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, past_boundary_aal2)
		with time.now_ns as stepup_now_ns
}

# …and the window is bounded BELOW too: `skew` tolerates a slightly-ahead IdP clock, but an
# `auth_time` further in the future than `skew` fails closed — deleting the lower-bound conjunct
# makes the beyond-skew case elevate forever (the one-sided `<=` is satisfied by any margin).
test_future_auth_time_within_skew_still_elevates if {
	product.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal2", "auth_time": stepup_now_s + 30},
	)
		with time.now_ns as stepup_now_ns
}

test_future_auth_time_beyond_skew_denies if {
	not product.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal2", "auth_time": stepup_now_s + 31},
	)
		with time.now_ns as stepup_now_ns
}

# U7 — THE UNPROVEN TIER IS ELEVATION-PROOF (Amendment 2). The absent-root clause carries no
# elevation conjunct, so an enrichment outage is closed for a freshly-elevated supervisor too:
# elevation proves who is present, never what the tier is.
test_unproven_tier_stays_closed_for_the_elevated if {
	not product.allow with input as elev_input(tiered_supervisor_role, absent_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
	not product.allow with input as elev_gate_input(tiered_supervisor_role, absent_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

# U8 — THE SOLE-BLOCKER `deny_reason` MATRIX (Amendment 1).
#
# PRESENT: supervised + production + not elevated + granted + no other deny.
test_deny_reason_present_when_step_up_is_the_sole_blocker if {
	product.deny_reason == {
		"type": "insufficient_user_authentication",
		"required_acr": "aal2",
		"max_age": 300,
	} with input as tier_input(tiered_supervisor_role, production_root)
		with time.now_ns as stepup_now_ns
}

test_deny_reason_present_on_the_type_level_gate if {
	product.deny_reason.type == "insufficient_user_authentication" with input as tier_gate_input(tiered_supervisor_role, production_root)
		with time.now_ns as stepup_now_ns
}

# …and its max_age is READ FROM DATA, so the challenge can never advertise a window the policy does
# not enforce (the freshness drill's leaf-path override rides exactly this).
test_deny_reason_max_age_comes_from_data if {
	product.deny_reason.max_age == 60 with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 60, "skew": 30}
		with time.now_ns as stepup_now_ns
}

# …and so is its required_acr: a deployment that renames the level-2 ACR in `loa` must see the
# challenge follow it — a hardcoded name would advertise an ACR the map no longer accepts and send
# the client into ADR 0030 §7's re-auth loop.
test_deny_reason_required_acr_comes_from_data if {
	product.deny_reason.required_acr == "silver" with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "silver": 2}, "required_acr": "silver", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
}

# The TYPE of the loa level is load-bearing: Rego comparisons are a total order ACROSS types
# (every string sorts above every number — `"1" >= 2` is TRUE), so without the `is_number` guard a
# string-valued loa map would elevate password-only logins instead of failing closed. The mixed map
# is the sharpest trap: a string level beside a numeric threshold satisfies the bare comparison.
# …and the THRESHOLD side needs its own guard: Rego's total order puts `null` BELOW every number,
# so a null-valued `loa[required_acr]` would make `1 >= null` true and an aal1 (password-only)
# subject would clear an aal2 threshold. The string-map case above cannot pin this — it trips the
# level-side guard first, so only a WELL-TYPED level beside a bad threshold reaches this conjunct.
test_non_numeric_required_level_fails_closed if {
	not product.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal1", "auth_time": stepup_now_s - 10},
	)
		with data.step_up as {"loa": {"aal1": 1, "aal2": null}, "required_acr": "aal2", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
}

test_string_loa_values_fail_closed if {
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with data.step_up as {"loa": {"aal1": "1", "aal2": "2"}, "required_acr": "aal2", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
	not product.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal1", "auth_time": stepup_now_s - 10},
	)
		with data.step_up as {"loa": {"aal1": "1", "aal2": 2}, "required_acr": "aal2", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
}

# The threshold is loa[required_acr], never a literal — and INCOHERENT data mutes the challenge:
# with required_acr unmapped in loa, nobody can elevate (fail-closed) AND no deny_reason is emitted
# (a challenge naming an ACR that elevates nothing is the §7 loop, not an answer).
test_unmapped_required_acr_closes_elevation_and_mutes_the_challenge if {
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "gold", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
	not product.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "gold", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
}

# …and the WINDOW axes get the same treatment: a string-valued max_age or an absent skew leaves
# `elevated` permanently undefined (the arithmetic type-errors / goes undefined), so the challenge
# must be muted too — emitting one would advertise a window no re-authentication can satisfy.
test_malformed_window_data_mutes_the_challenge if {
	not product.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": "300", "skew": 30}
		with time.now_ns as stepup_now_ns
	not product.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 300}
		with time.now_ns as stepup_now_ns

	# …and the SKEW axis explicitly. NOTE what this case does and does not pin: a string-valued skew
	# is muted by the `max_age + skew` ARITHMETIC (a type error → undefined), so this cell passes
	# with `is_number(skew)` deleted — it pins the BEHAVIOUR (incoherent window ⇒ no challenge), not
	# that particular conjunct, which is belt-and-braces. The decisive type guard is the one on
	# `loa[required_acr]`, where a comparison would otherwise order across types instead of erroring.
	not product.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 300, "skew": "30"}
		with time.now_ns as stepup_now_ns
}

# …and a DEAD window is muted: the enforced window is `max_age + skew`, so what must be unsatisfiable
# is the SUM. A sum <= 0 can never be reached by any re-authentication, so a challenge there is the
# §7 loop by arithmetic.
test_dead_window_mutes_the_challenge if {
	# negative max_age that the skew cannot rescue
	not product.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": -100, "skew": 30}
		with time.now_ns as stepup_now_ns

	# …and a negative skew large enough to invert an otherwise sane max_age
	not product.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 300, "skew": -400}
		with time.now_ns as stepup_now_ns

	# THE CASE A SUM-ONLY GUARD CANNOT SEE: a SMALL negative skew leaves the sum positive (290 > 0),
	# so a sum-only guard mints the challenge — but `elevated`'s lower bound is `auth_time - now <=
	# skew`, which a FRESH re-auth (age 0) fails against -10. The subject would re-authenticate and
	# be challenged again for |skew| seconds: §7's loop. The skew axis needs its own guard.
	not product.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 300, "skew": -10}
		with time.now_ns as stepup_now_ns
}

# CONVERSELY: a window that is small but LIVE still challenges. max_age=-1 with skew=30 leaves a
# 29-second window that a fresh re-auth genuinely satisfies, so muting it would deny the subject the
# one thing that would let them in — the mistake the previous `max_age >= 0` guard made.
test_small_but_live_window_still_challenges if {
	product.deny_reason.max_age == -1 with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": -1, "skew": 30}
		with time.now_ns as stepup_now_ns
}

# The EMPTY-DOCUMENT off-state, pinned: with `data.step_up` gone (the state a whole-document PUT
# clobber produces — the drill's footgun), elevation is impossible for a fresh aal2 AND the
# sole-blocker reason is undefined — a plain deny, never a challenge advertising a window nobody
# enforces. This is the "absent data.step_up leaves this undefined -> plain deny" contract stated
# beside `deny_reason`, asserted rather than trusted.
test_absent_step_up_data_closes_elevation_and_mutes_the_challenge if {
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with data.step_up as {}
		with time.now_ns as stepup_now_ns
	not product.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {}
		with time.now_ns as stepup_now_ns
}

# ABSENT — an ELEVATED subject has nothing to be challenged for.
test_no_deny_reason_when_already_elevated if {
	not product.deny_reason with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

# ABSENT — a WRITE verb: not `granted`, so the read-only ceiling answers plainly. A challenge here
# would promise that a second factor unlocks a write, which it never does.
#
# The subject is deliberately UNELEVATED (`tier_input`, no claims): with a fresh aal2 the whole
# clause is already dead at `stepup_denied`, so the case would pass with `granted` DELETED — it
# would duplicate test_no_deny_reason_when_already_elevated and pin nothing. Unelevated, this is
# the only cell that fails when the `granted` conjunct goes.
test_no_deny_reason_for_a_write_verb if {
	not product.deny_reason with input as object.union(
		tier_input(tiered_supervisor_role, production_root),
		{"action": "product:update"},
	)
		with time.now_ns as stepup_now_ns
}

# ABSENT — an OUT-OF-SCOPE supervisor: the second `granted` bullet. A supervised role that does not
# carry the verb on this type is not one elevation from allow, so it learns nothing — no challenge,
# no "this is production" leak.
# (Built literally, NOT via object.union on the supervisor fixture: object.union merges maps
# RECURSIVELY, so unioning `{"permissions": {}}` leaves the original permissions intact and the
# subject stays granted — the case would then assert nothing.)
out_of_scope_supervisor_role := {
	"code": "supervisor-readonly",
	"attributes": {"provenance": "supervised"},
	"permissions": {"catalog": ["READ"]},
}

test_no_deny_reason_for_an_out_of_scope_supervisor if {
	not product.deny_reason with input as tier_input(out_of_scope_supervisor_role, production_root)
		with time.now_ns as stepup_now_ns
}

# ABSENT — a MEMBER: the provenance conjunct means no membership decision can reach the clause.
test_no_deny_reason_for_a_member if {
	not product.deny_reason with input as tier_input(tiered_member_role, production_root)
		with time.now_ns as stepup_now_ns
}

# ABSENT — a non-production tier raises no step-up deny to explain.
test_no_deny_reason_on_staging_or_untagged_roots if {
	not product.deny_reason with input as tier_input(tiered_supervisor_role, staging_root)
		with time.now_ns as stepup_now_ns
	not product.deny_reason with input as tier_input(tiered_supervisor_role, untagged_root)
		with time.now_ns as stepup_now_ns
}

# ABSENT — an UNPROVEN tier: the outage deny is a `denied_other`, so the answer is a plain 403 and
# never a challenge the second factor could not satisfy.
test_no_deny_reason_during_an_enrichment_outage if {
	not product.deny_reason with input as elev_input(tiered_supervisor_role, absent_root, {"acr": "aal1", "auth_time": stepup_now_s - 10})
		with time.now_ns as stepup_now_ns
}

# ABSENT — ANOTHER DENY FIRES: an explicit `abac_deny` on the leaf is a `denied_other`, so the
# subject is not "exactly one elevation away from allow" and gets no challenge.
test_no_deny_reason_when_another_deny_fires if {
	not product.deny_reason with input as object.union(
		tier_input(tiered_supervisor_role, production_root),
		{"resource": {"attributes": {"abac_deny": true}}},
	)
		with time.now_ns as stepup_now_ns
}

# U9 — THE AGENT DENY AND ITS PRESENCE-TEST (Amendment 4). Supervised + an `act_chain` KEY denies at
# EVERY tier, and the discriminator is the key's presence, never its truthiness.
agent_claims := {"act_chain": ["agent-readonly"]}

test_agent_call_denied_on_every_tier if {
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, agent_claims)
	not product.allow with input as elev_input(tiered_supervisor_role, staging_root, agent_claims)
	not product.allow with input as elev_input(tiered_supervisor_role, untagged_root, agent_claims)
	not product.allow with input as elev_input(tiered_supervisor_role, absent_root, agent_claims)
	not product.allow with input as elev_gate_input(tiered_supervisor_role, untagged_root, agent_claims)
}

# THE RECORDED ESCAPE: a bare truthiness test would let `act_chain: false` through to the human
# branch. Every falsy/empty shape must still be an agent call.
test_agent_presence_test_survives_falsy_claim_values if {
	every value in [false, [], "", 0, null] {
		not product.allow with input as elev_input(
			tiered_supervisor_role, untagged_root,
			{"act_chain": value},
		)
	}
}

# …and a MEMBER's agent call is untouched: every clause is provenance-scoped, so the agent surface's
# existing member behaviour (ADR 0028) cannot be reached from here.
test_member_agent_call_unaffected if {
	product.allow with input as elev_input(tiered_member_role, untagged_root, agent_claims)
	product.allow with input as elev_input(tiered_member_role, production_root, agent_claims)
}

# U10 — AGENTS NEVER SEE A CHALLENGE, on a deliberately CONSTRUCTED input: on the rig this token is
# unmintable (the agent clients are ROPC-only, so `act_chain` and `auth_time` cannot coexist), which
# is exactly why the contract is pinned here instead of in a matrix cell. The agent deny is a
# `denied_other`, so the sole-blocker rule suppresses the reason — no TOTP treadmill for a caller
# that cannot TOTP.
test_agent_with_fresh_aal2_denied_and_never_challenged if {
	agent_elevated := object.union(fresh_aal2, agent_claims)
	not product.allow with input as elev_input(tiered_supervisor_role, production_root, agent_elevated)
		with time.now_ns as stepup_now_ns
	not product.deny_reason with input as elev_input(tiered_supervisor_role, production_root, agent_elevated)
		with time.now_ns as stepup_now_ns
}

# Nothing elevation- or agent-related may enter the residual: `filter` still answers TRUE on the very
# inputs the gate denies, so the two are visibly decided in different places (B's pin, re-asserted
# against BOTH of C's new discriminators).
test_elevation_and_agent_never_enter_the_filter_residual if {
	product.filter with input as elev_gate_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
	product.filter with input as elev_gate_input(tiered_supervisor_role, production_root, agent_claims)
}

# …and the defence-in-depth conjunct inside `elevated` itself is asserted DIRECTLY, not only through
# `allow`: the agent deny already closes every path, so a test that went through `allow` would stay
# green if the conjunct were deleted (measured — that mutation caught nothing). Elevation is a human
# ceremony, and this is the rule that says so.
test_elevation_is_a_human_ceremony if {
	not product.elevated with input as elev_input(
		tiered_supervisor_role, production_root,
		object.union(fresh_aal2, agent_claims),
	)
		with time.now_ns as stepup_now_ns
	product.elevated with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}
