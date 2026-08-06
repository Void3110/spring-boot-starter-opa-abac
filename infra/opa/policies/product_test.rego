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
		"attributes": {"provenance": "membership"},
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
