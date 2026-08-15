package category_test

import data.category

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
	category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:view",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_viewer_role_def_cannot_update if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:update",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_editor_role_def_views if {
	category.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "category:view",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

test_editor_role_def_updates if {
	category.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "category:update",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# --- P3: a stale flat token decides NOTHING (the clean cut's ∅-expansion floor) ---

stale_flat_role := {
	"code": "stale-viewer",
	"attributes": {"role_level": 10},
	"permissions": {"category": ["read", "write"]},
}

test_stale_flat_token_denies_everything_it_once_granted if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:view",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": stale_flat_role,
		"environment": {},
	}
	not category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:update",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": stale_flat_role,
		"environment": {},
	}
}

# --- P6: deny-overrides end-to-end — WRITE granted, delete denied -----------------

no_delete_editor := {
	"code": "no-delete-editor",
	"attributes": {"role_level": 20},
	"permissions": {"category": ["READ", "WRITE"]},
	"denied_actions": {"category": ["delete"]},
}

test_denied_action_update_still_allows if {
	category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:update",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": no_delete_editor,
		"environment": {},
	}
}

test_denied_action_delete_denies if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:delete",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": no_delete_editor,
		"environment": {},
	}
}

# The abac_deny resource veto is UNCHANGED by the category model (a separate deny mechanism).
test_abac_deny_veto_beats_category_grant if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:view",
		"resource": {"type": "category", "id": "p1", "attributes": {"abac_deny": true}},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

# --- Slice B4: realm-role fallback REMOVED (ADR 0018) -----------------------
#
# A category lives under a governed catalog, so the resolved role (team membership) applies at every
# level; the blanket realm fallback only leaked. With no role_definition, a bare realm role is now
# denied at EVERY verb — closing the deep-link leak (R7). There is no create-style narrow fallback
# here (a category is created under an already-governed catalog).

# R7 — bare catalog-viewer (no role-def) can no longer view a category.
test_fallback_viewer_view_now_denied if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:view",
		"resource": {"type": "category", "id": "p1"},
		"environment": {},
	}
}

# R7 — bare catalog-editor (no role-def) can no longer update a category (the leak we closed).
test_fallback_editor_update_now_denied if {
	not category.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "category:update",
		"resource": {"type": "category", "id": "p1"},
		"environment": {},
	}
}

# The resolved-role path is UNCHANGED: a role-def granting WRITE still updates.
test_resolved_role_updates_unchanged if {
	category.allow with input as {
		"subject": {"id": "u2", "roles": []},
		"action": "category:update",
		"resource": {"type": "category", "id": "p1"},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# --- Slice B4: the coarse CREATE gate (type-level, role resolved on the parent catalog) ---------
#
# category:create is type-level (the child has no id yet). The gate resolves the role on the PARENT
# catalog (the governing root, via the @OpaPreAuthorize roleResource override) — so a role granting
# `create` on the inheritable catalog ancestor opens the gate. A non-member (no role-def) is denied.

category_create_input(role_def) := {
	"subject": {"id": "u", "roles": ["catalog-editor"]},
	"action": "category:create",
	"resource": {"type": "category"}, # type-level — no id
	"role_definition": role_def,
	"environment": {},
}

# A catalog-resolved role granting WRITE on the catalog (create ∈ WRITE) opens category:create.
test_create_inheritable_grant_opens_gate if {
	category.allow with input as category_create_input(editor_role_def)
		with data.category.inheritable as {"category": {"catalog": true}}
}

# A read-only catalog role does NOT grant create (READ has no create).
test_create_inheritable_grant_read_only_denied if {
	not category.allow with input as category_create_input(viewer_role_def)
		with data.category.inheritable as {"category": {"catalog": true}}
}

# A non-member (NO role-def) is denied category:create — the fix opens the gate only for a resolved role.
test_create_no_role_def_denied if {
	not category.allow with input as {
		"subject": {"id": "u", "roles": ["catalog-editor"]},
		"action": "category:create",
		"resource": {"type": "category"},
		"environment": {},
	}
		with data.category.inheritable as {"category": {"catalog": true}}
}

# Tag-on-create: the TYPE-LEVEL category:assign-tags gate (no instance yet) also opens via the parent
# catalog's TAG grant — the same verb-agnostic inheritable clause. A non-member is denied.
test_assign_tags_for_create_inheritable_opens if {
	category.allow with input as category_create_input(editor_role_def)
		with input.action as "category:assign-tags"
		with data.category.inheritable as {"category": {"catalog": true}}
}

test_assign_tags_for_create_no_role_denied if {
	not category.allow with input as {
		"subject": {"id": "u", "roles": ["catalog-editor"]},
		"action": "category:assign-tags",
		"resource": {"type": "category"},
		"environment": {},
	}
		with data.category.inheritable as {"category": {"catalog": true}}
}

# REAL WIRE SHAPE — explicit `"id": null` (the regression-protection for the null-safe
# is_type_level_request clause). AbacContext.Resource has no @JsonInclude(NON_NULL) on `id`, so a
# type-level decision serializes as {"type":"category","id":null,...}, NOT an omitted key. The
# helpers above use the omitted shape (exercising only `not input.resource.id`); these pin the
# `input.resource.id == null` clause specifically. If that clause is dropped, the omitted-shape
# tests still pass but these FAIL — and production (which always emits id:null) would silently
# deny every member's type-level create/list/assign-tags. See category.rego is_type_level_request.
category_create_input_null_id(role_def) := {
	"subject": {"id": "u", "roles": ["catalog-editor"]},
	"action": "category:create",
	"resource": {"type": "category", "id": null, "attributes": {}}, # type-level — explicit null id
	"role_definition": role_def,
	"environment": {},
}

# A catalog-resolved WRITE role opens the gate even with the explicit-null wire shape.
test_create_null_id_inheritable_grant_opens_gate if {
	category.allow with input as category_create_input_null_id(editor_role_def)
		with data.category.inheritable as {"category": {"catalog": true}}
}

# A non-member (NO role-def) is denied on the explicit-null wire shape too (isolation holds).
test_create_null_id_no_role_def_denied if {
	not category.allow with input as {
		"subject": {"id": "u", "roles": ["catalog-editor"]},
		"action": "category:create",
		"resource": {"type": "category", "id": null, "attributes": {}},
		"environment": {},
	}
		with data.category.inheritable as {"category": {"catalog": true}}
}

# --- default deny -----------------------------------------------------------

test_default_deny_unknown_role if {
	not category.allow with input as {
		"subject": {"id": "u3", "roles": ["random-role"]},
		"action": "category:view",
		"resource": {"type": "category", "id": "p1"},
		"environment": {},
	}
}

test_default_deny_no_roles_no_role_def if {
	not category.allow with input as {
		"subject": {"id": "u3", "roles": []},
		"action": "category:update",
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
	"permissions": {"category": ["READ"]},
	"required_tags": {"region": ["emea"]},
	"match_mode": "ANY_OF",
}

# requires region:[emea] AND sensitivity:[public, internal] (both must hold)
strict_reader_all := {
	"code": "strict-reader",
	"attributes": {"role_level": 15},
	"permissions": {"category": ["READ"]},
	"required_tags": {"region": ["emea"], "sensitivity": ["public", "internal"]},
	"match_mode": "ALL_OF",
}

# requires region:[emea] OR sensitivity:[public] (either acceptable)
multi_region_any := {
	"code": "multi-region",
	"attributes": {"role_level": 15},
	"permissions": {"category": ["READ"]},
	"required_tags": {"region": ["emea"], "sensitivity": ["public"]},
	"match_mode": "ANY_OF",
}

tag_input(role_def, tags) := {
	"subject": {"id": "u1", "roles": []},
	"action": "category:view",
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

# T6 — no required tags (vacuous): a plain role behaves exactly as before (allow on view).
test_vacuous_no_required_tags if {
	category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:view",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": ["apac"]}},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

# T7 — permission ok but tags fail -> deny (both checks required).
test_permission_ok_tags_fail if {
	not category.allow with input as tag_input(regional_reader_any, {"region": ["apac"]})
}

# T8 — tags ok but permission absent (update not granted) -> deny.
test_tags_ok_permission_fail if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:update",
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
			"permissions": {"category": ["READ"]},
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
			"permissions": {"category": ["READ"]},
			"required_tags": {"region": ["emea"]},
		},
		{"region": ["emea"]},
	)
}

# T10 — default deny still holds with required tags + a missing resource tag.
test_default_deny_missing_resource_tag if {
	not category.allow with input as tag_input(regional_reader_any, {"sensitivity": "public"})
}

# P11 — tag matching composes with expansion unchanged: ANY_OF's second key rescues.
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
# Java HttpOpaClientCompileTest + the P10 fold + the e2e matrix). The decisive properties: filter is
# ROLE-DEFINITION-ONLY (no subject-roles fallback) so a missing role definition fails CLOSED; the
# category expansion is consumed INLINE (the PE-friendly idiom — same data table as effective_actions);
# "list" must be in the expanded-minus-denied set; and its membership tag match matches a scalar OR an
# array tag — agreeing with the single-decision `allow`.

# An unrestricted role (no required tags) -> filter true for any listable category.
test_filter_unrestricted_lists if {
	category.filter with input as {
		"action": "category:list",
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
# `filter` was always role-def-only; a list with no role definition is empty, never the whole table.
test_filter_no_role_definition_denies if {
	not category.filter with input as {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:list",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"environment": {},
	}
}

# Slice B4: `allow` now AGREES with `filter` for a no-role-def request — the realm fallback that used
# to grant a view (and made the list/single-GET disagree) was removed. Both deny: membership is the
# sole access path, so a single-GET by a non-member fails closed at the category level too.
test_allow_and_filter_both_deny_no_role_def if {
	req := {
		"subject": {"id": "u1", "roles": ["catalog-viewer"]},
		"action": "category:view",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"environment": {},
	}
	not category.allow with input as req
	not category.filter with input as req
}

# P8 — filter requires "list" in the EFFECTIVE set: a TAG-only role (no READ) -> filter false.
test_filter_tag_only_role_denies if {
	not category.filter with input as {
		"action": "category:list",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": {"code": "x", "permissions": {"category": ["TAG"]}},
		"environment": {},
	}
}

# P8 — a grant on a DIFFERENT type does not open this type's list.
test_filter_requires_grant_on_this_type if {
	not category.filter with input as {
		"action": "category:list",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": {"code": "x", "permissions": {"catalog": ["READ"]}},
		"environment": {},
	}
}

# P8 — a denial of "list" closes the filter even though READ grants it (deny-overrides at the
# list boundary).
test_filter_list_denied_closes if {
	not category.filter with input as {
		"action": "category:list",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": {
			"code": "x",
			"permissions": {"category": ["READ"]},
			"denied_actions": {"category": ["list"]},
		},
		"environment": {},
	}
}

# A stale flat token never opens the filter (∅-expansion at the list boundary).
test_filter_stale_flat_token_denies if {
	not category.filter with input as {
		"action": "category:list",
		"resource": {"type": "category", "id": "p1", "attributes": {"region": "emea"}},
		"role_definition": stale_flat_role,
		"environment": {},
	}
}

# --- Phase 5: bulk entrypoint (batch / allowlist primitive) ------------------

# bulk returns a positional list of allow-decisions over input.items.
test_bulk_returns_positional_decisions if {
	result := category.bulk with input as {"items": [
		{
			"subject": {"id": "u", "roles": []},
			"action": "category:view",
			"resource": {"type": "category", "id": "a", "attributes": {}},
			"role_definition": viewer_role_def,
			"environment": {},
		},
		{
			"subject": {"id": "u", "roles": []},
			"action": "category:update",
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

# A role resolved on the governing root (a Catalog) grants READ on the catalog type only.
cat_root_role := {
	"code": "catalog-viewer",
	"attributes": {"provenance": "membership"}, # ADR 0031 — membership-derived, so inheritance applies
	"permissions": {"catalog": ["READ"]},
}

# category inherits from catalog (opt-in, default-off): supplied per-test via `with data`.
category_inherits_catalog := {"category": {"catalog": true}}

deep_category_input(role_def, attrs) := {
	"subject": {"id": "u1", "roles": ["catalog-viewer"]},
	"action": "category:view",
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
		"action": "category:view",
		"resource": {"type": "category", "id": "k1", "attributes": {}},
		"role_definition": cat_root_role,
		"environment": {},
	}
		with data.category.inheritable as category_inherits_catalog
}

# A DENIAL on the ancestor type narrows the INHERITED grant too (effective_actions on the
# ancestor type is expanded-minus-denied).
test_inherited_grant_respects_ancestor_denial if {
	not category.allow with input as deep_category_input(
		{
			"code": "no-view-root",
			"attributes": {"provenance": "membership"},
			"permissions": {"catalog": ["READ"]},
			"denied_actions": {"catalog": ["view"]},
		},
		{},
	)
		with data.category.inheritable as category_inherits_catalog
}

# TAG MATCH ON THE INHERITED PATH: a tag-gated root role only inherits where the LEAF's tags satisfy it.
cat_root_role_tagged := {
	"code": "regional-catalog-reader",
	"attributes": {"provenance": "membership"}, # ADR 0031 — membership-derived, so inheritance applies
	"permissions": {"catalog": ["READ"]},
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

# --- Phase 5.5-B: the COARSE LIST GATE (no resource id, inheritable ancestor grant) ----------------

# The type-level @OpaPreAuthorize on a LIST endpoint asks `allow` with only a resource TYPE (no id).
list_gate_input(role_def) := {
	"subject": {"id": "u1", "roles": []},
	"action": "category:list",
	"resource": {"type": "category"},
	"role_definition": role_def,
	"environment": {},
}

# P9 — a catalog-only role passes the COARSE list gate (it may list categories via the inheritable
# catalog grant — "list" opens it exactly as "read" did pre-6.5). The FINE which-rows cut still
# happens in SQL — this clause only OPENS the gate.
test_list_gate_passes_for_inheritable_ancestor_grant if {
	category.allow with input as list_gate_input(cat_root_role)
		with data.category.inheritable as category_inherits_catalog
}

# OPT-IN OFF: same catalog-only role, EMPTY inheritable data → the list gate denies (no inheritance).
test_list_gate_denies_when_inheritance_off if {
	not category.allow with input as list_gate_input(cat_root_role)
		with data.category.inheritable as {}
}

# A stranger (no role definition) is still denied at the list gate (fail-closed boundary).
test_list_gate_denies_stranger if {
	not category.allow with input as {
		"subject": {"id": "s", "roles": []},
		"action": "category:list",
		"resource": {"type": "category"},
		"environment": {},
	}
		with data.category.inheritable as category_inherits_catalog
}

# A leaf deny on the (hypothetical) list context still overrides — the gate AND-s `not denied`.
test_list_gate_respects_deny if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:list",
		"resource": {"type": "category", "attributes": {"abac_deny": true}},
		"role_definition": cat_root_role,
		"environment": {},
	}
		with data.category.inheritable as category_inherits_catalog
}

# SINGLE-RESOURCE unchanged: a catalog-only role on a category WITH an id but NO ancestors → still deny
# (the list-gate clause requires NO id, so it does not loosen single-resource decisions).
test_list_gate_does_not_affect_single_resource if {
	not category.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "category:view",
		"resource": {"type": "category", "id": "k1", "attributes": {}},
		"role_definition": cat_root_role,
		"environment": {},
	}
		with data.category.inheritable as category_inherits_catalog
}

# A DENIAL of "list" on the ancestor type closes the coarse list gate (the gate consumes
# effective_actions on the ancestor type too).
test_list_gate_respects_ancestor_denial if {
	not category.allow with input as list_gate_input({
		"code": "no-list-root",
		"attributes": {"provenance": "membership"},
		"permissions": {"catalog": ["READ"]},
		"denied_actions": {"catalog": ["list"]},
	})
		with data.category.inheritable as category_inherits_catalog
}

# --- ADR 0031: ancestor inheritance is confined to membership-derived roles (U35, U37–U40) ---------
#
# The defect this closes was PROVEN BY EVALUATION, not argued: with the shipped inheritance tables, a
# synthesized role granting only `catalog: ["READ"]` inherited `category:view` from the very catalog it
# may read, because inheritance keys on the VERB NAME across types. Before the conjunct these probes
# returned true. NOTE the shape: the ancestor chain must be PRESENT, because an ancestor-less probe
# returns false for the wrong reason — which is exactly how the fail-open was green-lit in planning.

# The synthesized supervisor role of ADR 0029: the coarse READ token on the ancestor type only, and the
# provenance marker saying it was NOT derived from a membership.
supervisor_role := {
	"code": "supervisor-readonly",
	"attributes": {"provenance": "supervised"},
	"permissions": {"catalog": ["READ"]},
}

# U35 — the supervisor role + a category CARRYING ITS CATALOG ANCESTOR (the runtime input shape).
# This same probe returned TRUE before ADR 0031.
test_supervised_role_cannot_inherit_category_view if {
	not category.allow with input as deep_category_input(supervisor_role, {})
		with data.category.inheritable as category_inherits_catalog
}

# U37 — the COARSE type-level gate is confined too, so it cannot open what the fine cut would deny.
test_supervised_role_cannot_open_the_type_level_list_gate if {
	not category.allow with input as list_gate_input(supervisor_role)
		with data.category.inheritable as category_inherits_catalog
}

# U38 — THE REGRESSION CASE for the whole change: a WILDCARD-derived membership role (the resolve API
# expands "*" to the requested type, so it arrives byte-identical in shape to the supervisor role) is
# stamped `membership` and KEEPS the inheritance it has today. This is the constituency that
# legitimately depends on the mechanism.
test_wildcard_membership_role_keeps_inheritance if {
	category.allow with input as deep_category_input(
		{
			"code": "catalog-owner",
			"attributes": {"provenance": "membership"},
			# what a stored {"*": [...]} role looks like AFTER resolve-side wildcard expansion
			"permissions": {"catalog": ["READ", "WRITE", "TAG", "GRANT"]},
		},
		{},
	)
		with data.category.inheritable as category_inherits_catalog
}

# U39 — a role naming `category` EXPLICITLY reaches it through `direct_grant` with NO stamp at all:
# the conjunct touches only the inheritance path, which is why every shipped per-type role is
# unaffected and why a later slice that widens the synthesized role by naming child types needs no
# policy change.
test_direct_grant_needs_no_provenance_stamp if {
	category.allow with input as deep_category_input(
		{
			"code": "category-editor",
			"attributes": {},
			"permissions": {"category": ["READ"]},
		},
		{},
	)
		with data.category.inheritable as category_inherits_catalog
}

# U40 — ABSENCE IS CLOSED. No `attributes` at all, an empty map, and an UNKNOWN provenance value each
# fail the conjunct, so a future synthesized role that forgets the stamp fails CLOSED (a visible
# missing-access bug) rather than open.
test_absent_or_unknown_provenance_grants_no_inheritance if {
	not category.allow with input as deep_category_input(
		{"code": "no-attrs", "permissions": {"catalog": ["READ"]}},
		{},
	)
		with data.category.inheritable as category_inherits_catalog

	not category.allow with input as deep_category_input(
		{"code": "empty-attrs", "attributes": {}, "permissions": {"catalog": ["READ"]}},
		{},
	)
		with data.category.inheritable as category_inherits_catalog

	not category.allow with input as deep_category_input(
		{
			"code": "future-role",
			"attributes": {"provenance": "elevated"}, # a value this policy does not know
			"permissions": {"catalog": ["READ"]},
		},
		{},
	)
		with data.category.inheritable as category_inherits_catalog
}

# --- PRODUCTION TIER (ADR 0030 §3–4, ADR 0032) — U6, U7, U10 ---------------------------------------
#
# The supervisor role WIDENED by slice B: it now names `category` and `product` explicitly, so child
# reads reach through `direct_grant` — no inheritance, so ADR 0031's conjuncts above are untouched and
# their tests still assert exactly what they asserted. What decides how deep the read goes is the
# governing root's env tag, carried as input.resource.root_attributes.
tiered_supervisor_role := {
	"code": "supervisor-readonly",
	"attributes": {"provenance": "supervised"},
	"permissions": {
		"catalog": ["READ"],
		"category": ["READ"],
		"product": ["READ"],
	},
}

# The same shape, but membership-derived — the control for "members are structurally unaffected".
tiered_member_role := {
	"code": "catalog-owner",
	"attributes": {"provenance": "membership"},
	"permissions": {
		"catalog": ["READ"],
		"category": ["READ"],
		"product": ["READ"],
	},
}

# A role with NO provenance at all — the other half of "cannot reach the tier clauses".
unstamped_role := {
	"code": "category-editor",
	"permissions": {"category": ["READ"]},
}

# The instance shape: a category carrying its catalog ancestor, plus a root_attributes STATE.
# `root_state` is an object patched over the resource, so the ABSENT case is a genuinely missing key
# rather than a null — the distinction the whole contract rests on.
tier_input(role_def, root_state) := {
	"subject": {"id": "u1", "roles": []},
	"action": "category:view",
	"resource": object.union(
		{
			"type": "category",
			"id": "k1",
			"attributes": {},
			"ancestors": [{"type": "catalog", "id": "c1"}],
		},
		root_state,
	),
	"role_definition": role_def,
	"environment": {},
}

# The type-level coarse gate shape with a root_attributes state (the child LIST path).
tier_gate_input(role_def, root_state) := {
	"subject": {"id": "u1", "roles": []},
	"action": "category:list",
	"resource": object.union({"type": "category"}, root_state),
	"role_definition": role_def,
	"environment": {},
}

absent_root := {}

untagged_root := {"root_attributes": {}}

staging_root := {"root_attributes": {"env": "staging"}}

production_root := {"root_attributes": {"env": "production"}}

# U6 — the instance shape, all four tier states.
test_tier_instance_absent_root_denies if {
	not category.allow with input as tier_input(tiered_supervisor_role, absent_root)
}

test_tier_instance_untagged_root_allows if {
	category.allow with input as tier_input(tiered_supervisor_role, untagged_root)
}

test_tier_instance_staging_root_allows if {
	category.allow with input as tier_input(tiered_supervisor_role, staging_root)
}

test_tier_instance_production_root_denies if {
	not category.allow with input as tier_input(tiered_supervisor_role, production_root)
}

# U7 — the SAME four states through the coarse type-level list gate.
test_tier_gate_absent_root_denies if {
	not category.allow with input as tier_gate_input(tiered_supervisor_role, absent_root)
}

test_tier_gate_untagged_root_allows if {
	category.allow with input as tier_gate_input(tiered_supervisor_role, untagged_root)
}

test_tier_gate_staging_root_allows if {
	category.allow with input as tier_gate_input(tiered_supervisor_role, staging_root)
}

test_tier_gate_production_root_denies if {
	not category.allow with input as tier_gate_input(tiered_supervisor_role, production_root)
}

# U10 — MEMBERS ARE STRUCTURALLY UNAFFECTED (ADR 0030 §2). A membership-derived role reads its own
# team's PRODUCTION catalog's contents, and keeps reading them during an enrichment outage (absent
# root_attributes) — the state that closes every supervised read. Same for a role carrying no
# provenance at all. Both clauses require provenance == "supervised", so neither decision can reach
# them: this is the conjunct's whole job.
test_member_unaffected_by_production_tier if {
	category.allow with input as tier_input(tiered_member_role, production_root)
	category.allow with input as tier_gate_input(tiered_member_role, production_root)
}

test_member_unaffected_by_absent_root_attributes if {
	category.allow with input as tier_input(tiered_member_role, absent_root)
	category.allow with input as tier_gate_input(tiered_member_role, absent_root)
}

test_unstamped_role_unaffected_by_the_tier if {
	category.allow with input as tier_input(unstamped_role, production_root)
	category.allow with input as tier_input(unstamped_role, absent_root)
}

# The supervised path keeps its READ-only ceiling under the tier: a write verb is denied on a
# perfectly open (staging) root, so the widening opened reads and nothing else.
test_tier_does_not_widen_the_read_only_ceiling if {
	not category.allow with input as object.union(
		tier_input(tiered_supervisor_role, staging_root),
		{"action": "category:update"},
	)
}

# THE SHAPE TRAP's cardinality twin: an array-shaped env must deny exactly as the scalar does — a
# bare scalar `==` failed OPEN on ["production"] (neither deny clause fired). A non-production array
# stays open: the normalization must not over-close either.
array_production_root := {"root_attributes": {"env": ["production", "staging"]}}

array_non_production_root := {"root_attributes": {"env": ["staging", "dev"]}}

test_tier_instance_array_production_root_denies if {
	not category.allow with input as tier_input(tiered_supervisor_role, array_production_root)
}

test_tier_gate_array_production_root_denies if {
	not category.allow with input as tier_gate_input(tiered_supervisor_role, array_production_root)
}

test_tier_array_non_production_root_allows if {
	category.allow with input as tier_input(tiered_supervisor_role, array_non_production_root)
}

# The tier decision lands at the coarse gate and NEVER in the list residual: `filter` does not consult
# `denied`, so a root_attributes predicate can never reach the SQL. Asserted on the shape a supervised
# list actually compiles with — production root, which the gate above denies — so the two are visibly
# decided in different places. (Mirror of the sibling's pin — per-type policies drift, so each file
# carries its own proof rather than trusting the sibling's.)
test_tier_never_enters_the_filter_residual if {
	category.filter with input as tier_gate_input(tiered_supervisor_role, production_root)
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
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, {"auth_time": stepup_now_s - 10})
		with time.now_ns as stepup_now_ns
}

# U2 — `acr: aal2` but no `auth_time`: the arithmetic is undefined, so freshness is unproven.
test_elevation_undefined_without_auth_time if {
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, {"acr": "aal2"})
		with time.now_ns as stepup_now_ns
}

# U3 — an `acr` the LoA map does not name is not a level, and a mapped level BELOW 2 is not enough.
test_elevation_undefined_on_unmapped_acr if {
	not category.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "gold", "auth_time": stepup_now_s - 10},
	)
		with time.now_ns as stepup_now_ns
}

test_elevation_denied_on_acr_below_level_two if {
	not category.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal1", "auth_time": stepup_now_s - 10},
	)
		with time.now_ns as stepup_now_ns
}

# The same fail-closed floor for a TYPE-COERCED auth_time — the trap a `to_number` "fix" would open.
test_elevation_undefined_on_non_numeric_auth_time if {
	not category.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal2", "auth_time": "1786000000"},
	)
		with time.now_ns as stepup_now_ns
}

# U4 — THE HEADLINE: a fresh aal2 opens the production tier, on the instance shape AND the coarse
# type-level gate. This is the ONLY clause elevation narrows.
test_fresh_aal2_opens_production_instance if {
	category.allow with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

test_fresh_aal2_opens_production_gate if {
	category.allow with input as elev_gate_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

# …and on the array-shaped env too — the cardinality twin must not become an elevation hole.
test_fresh_aal2_opens_array_shaped_production if {
	category.allow with input as elev_input(tiered_supervisor_role, array_production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

# U5 — a stale `auth_time` denies: the elevation expires on its own, and a refresh cannot launder it
# (the refreshed token carries the SAME auth_time — measured on the rig, ADR 0030 §Context).
test_stale_auth_time_denies if {
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, stale_aal2)
		with time.now_ns as stepup_now_ns
}

# U6 — the window boundary is inclusive: exactly max_age + skew allows, one second past denies.
test_window_boundary_is_inclusive if {
	category.allow with input as elev_input(tiered_supervisor_role, production_root, boundary_aal2)
		with time.now_ns as stepup_now_ns
}

test_one_second_past_the_boundary_denies if {
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, past_boundary_aal2)
		with time.now_ns as stepup_now_ns
}

# …and the window is bounded BELOW too: `skew` tolerates a slightly-ahead IdP clock, but an
# `auth_time` further in the future than `skew` fails closed — deleting the lower-bound conjunct
# makes the beyond-skew case elevate forever (the one-sided `<=` is satisfied by any margin).
test_future_auth_time_within_skew_still_elevates if {
	category.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal2", "auth_time": stepup_now_s + 30},
	)
		with time.now_ns as stepup_now_ns
}

test_future_auth_time_beyond_skew_denies if {
	not category.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal2", "auth_time": stepup_now_s + 31},
	)
		with time.now_ns as stepup_now_ns
}

# U7 — THE UNPROVEN TIER IS ELEVATION-PROOF (Amendment 2). The absent-root clause carries no
# elevation conjunct, so an enrichment outage is closed for a freshly-elevated supervisor too:
# elevation proves who is present, never what the tier is.
test_unproven_tier_stays_closed_for_the_elevated if {
	not category.allow with input as elev_input(tiered_supervisor_role, absent_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
	not category.allow with input as elev_gate_input(tiered_supervisor_role, absent_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}

# U8 — THE SOLE-BLOCKER `deny_reason` MATRIX (Amendment 1).
#
# PRESENT: supervised + production + not elevated + granted + no other deny.
test_deny_reason_present_when_step_up_is_the_sole_blocker if {
	category.deny_reason == {
		"type": "insufficient_user_authentication",
		"required_acr": "aal2",
		"max_age": 300,
	} with input as tier_input(tiered_supervisor_role, production_root)
		with time.now_ns as stepup_now_ns
}

test_deny_reason_present_on_the_type_level_gate if {
	category.deny_reason.type == "insufficient_user_authentication" with input as tier_gate_input(tiered_supervisor_role, production_root)
		with time.now_ns as stepup_now_ns
}

# …and its max_age is READ FROM DATA, so the challenge can never advertise a window the policy does
# not enforce (the freshness drill's leaf-path override rides exactly this).
test_deny_reason_max_age_comes_from_data if {
	category.deny_reason.max_age == 60 with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 60, "skew": 30}
		with time.now_ns as stepup_now_ns
}

# …and so is its required_acr: a deployment that renames the level-2 ACR in `loa` must see the
# challenge follow it — a hardcoded name would advertise an ACR the map no longer accepts and send
# the client into ADR 0030 §7's re-auth loop.
test_deny_reason_required_acr_comes_from_data if {
	category.deny_reason.required_acr == "silver" with input as tier_input(tiered_supervisor_role, production_root)
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
	not category.allow with input as elev_input(
		tiered_supervisor_role, production_root,
		{"acr": "aal1", "auth_time": stepup_now_s - 10},
	)
		with data.step_up as {"loa": {"aal1": 1, "aal2": null}, "required_acr": "aal2", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
}

test_string_loa_values_fail_closed if {
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with data.step_up as {"loa": {"aal1": "1", "aal2": "2"}, "required_acr": "aal2", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
	not category.allow with input as elev_input(
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
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "gold", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
	not category.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "gold", "max_age": 300, "skew": 30}
		with time.now_ns as stepup_now_ns
}

# …and the WINDOW axes get the same treatment: a string-valued max_age or an absent skew leaves
# `elevated` permanently undefined (the arithmetic type-errors / goes undefined), so the challenge
# must be muted too — emitting one would advertise a window no re-authentication can satisfy.
test_malformed_window_data_mutes_the_challenge if {
	not category.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": "300", "skew": 30}
		with time.now_ns as stepup_now_ns
	not category.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 300}
		with time.now_ns as stepup_now_ns

	# …and the SKEW axis explicitly. NOTE what this case does and does not pin: a string-valued skew
	# is muted by the `max_age + skew` ARITHMETIC (a type error → undefined), so this cell passes
	# with `is_number(skew)` deleted — it pins the BEHAVIOUR (incoherent window ⇒ no challenge), not
	# that particular conjunct, which is belt-and-braces. The decisive type guard is the one on
	# `loa[required_acr]`, where a comparison would otherwise order across types instead of erroring.
	not category.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 300, "skew": "30"}
		with time.now_ns as stepup_now_ns
}

# …and a DEAD window is muted: the enforced window is `max_age + skew`, so what must be unsatisfiable
# is the SUM. A sum <= 0 can never be reached by any re-authentication, so a challenge there is the
# §7 loop by arithmetic.
test_dead_window_mutes_the_challenge if {
	# negative max_age that the skew cannot rescue
	not category.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": -100, "skew": 30}
		with time.now_ns as stepup_now_ns

	# …and a negative skew large enough to invert an otherwise sane max_age
	not category.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 300, "skew": -400}
		with time.now_ns as stepup_now_ns

	# THE CASE A SUM-ONLY GUARD CANNOT SEE: a SMALL negative skew leaves the sum positive (290 > 0),
	# so a sum-only guard mints the challenge — but `elevated`'s lower bound is `auth_time - now <=
	# skew`, which a FRESH re-auth (age 0) fails against -10. The subject would re-authenticate and
	# be challenged again for |skew| seconds: §7's loop. The skew axis needs its own guard.
	not category.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": 300, "skew": -10}
		with time.now_ns as stepup_now_ns
}

# CONVERSELY: a window that is small but LIVE still challenges. max_age=-1 with skew=30 leaves a
# 29-second window that a fresh re-auth genuinely satisfies, so muting it would deny the subject the
# one thing that would let them in — the mistake the previous `max_age >= 0` guard made.
test_small_but_live_window_still_challenges if {
	category.deny_reason.max_age == -1 with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {"loa": {"aal1": 1, "aal2": 2}, "required_acr": "aal2", "max_age": -1, "skew": 30}
		with time.now_ns as stepup_now_ns
}

# The EMPTY-DOCUMENT off-state, pinned: with `data.step_up` gone (the state a whole-document PUT
# clobber produces — the drill's footgun), elevation is impossible for a fresh aal2 AND the
# sole-blocker reason is undefined — a plain deny, never a challenge advertising a window nobody
# enforces. This is the "absent data.step_up leaves this undefined -> plain deny" contract stated
# beside `deny_reason`, asserted rather than trusted.
test_absent_step_up_data_closes_elevation_and_mutes_the_challenge if {
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with data.step_up as {}
		with time.now_ns as stepup_now_ns
	not category.deny_reason with input as tier_input(tiered_supervisor_role, production_root)
		with data.step_up as {}
		with time.now_ns as stepup_now_ns
}

# ABSENT — an ELEVATED subject has nothing to be challenged for.
test_no_deny_reason_when_already_elevated if {
	not category.deny_reason with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
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
	not category.deny_reason with input as object.union(
		tier_input(tiered_supervisor_role, production_root),
		{"action": "category:update"},
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
	not category.deny_reason with input as tier_input(out_of_scope_supervisor_role, production_root)
		with time.now_ns as stepup_now_ns
}

# ABSENT — a MEMBER: the provenance conjunct means no membership decision can reach the clause.
test_no_deny_reason_for_a_member if {
	not category.deny_reason with input as tier_input(tiered_member_role, production_root)
		with time.now_ns as stepup_now_ns
}

# ABSENT — a non-production tier raises no step-up deny to explain.
test_no_deny_reason_on_staging_or_untagged_roots if {
	not category.deny_reason with input as tier_input(tiered_supervisor_role, staging_root)
		with time.now_ns as stepup_now_ns
	not category.deny_reason with input as tier_input(tiered_supervisor_role, untagged_root)
		with time.now_ns as stepup_now_ns
}

# ABSENT — an UNPROVEN tier: the outage deny is a `denied_other`, so the answer is a plain 403 and
# never a challenge the second factor could not satisfy.
test_no_deny_reason_during_an_enrichment_outage if {
	not category.deny_reason with input as elev_input(tiered_supervisor_role, absent_root, {"acr": "aal1", "auth_time": stepup_now_s - 10})
		with time.now_ns as stepup_now_ns
}

# ABSENT — ANOTHER DENY FIRES: an explicit `abac_deny` on the leaf is a `denied_other`, so the
# subject is not "exactly one elevation away from allow" and gets no challenge.
test_no_deny_reason_when_another_deny_fires if {
	not category.deny_reason with input as object.union(
		tier_input(tiered_supervisor_role, production_root),
		{"resource": {"attributes": {"abac_deny": true}}},
	)
		with time.now_ns as stepup_now_ns
}

# U9 — THE AGENT DENY AND ITS PRESENCE-TEST (Amendment 4). Supervised + an `act_chain` KEY denies at
# EVERY tier, and the discriminator is the key's presence, never its truthiness.
agent_claims := {"act_chain": ["agent-readonly"]}

test_agent_call_denied_on_every_tier if {
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, agent_claims)
	not category.allow with input as elev_input(tiered_supervisor_role, staging_root, agent_claims)
	not category.allow with input as elev_input(tiered_supervisor_role, untagged_root, agent_claims)
	not category.allow with input as elev_input(tiered_supervisor_role, absent_root, agent_claims)
	not category.allow with input as elev_gate_input(tiered_supervisor_role, untagged_root, agent_claims)
}

# THE RECORDED ESCAPE: a bare truthiness test would let `act_chain: false` through to the human
# branch. Every falsy/empty shape must still be an agent call.
test_agent_presence_test_survives_falsy_claim_values if {
	every value in [false, [], "", 0, null] {
		not category.allow with input as elev_input(
			tiered_supervisor_role, untagged_root,
			{"act_chain": value},
		)
	}
}

# …and a MEMBER's agent call is untouched: every clause is provenance-scoped, so the agent surface's
# existing member behaviour (ADR 0028) cannot be reached from here.
test_member_agent_call_unaffected if {
	category.allow with input as elev_input(tiered_member_role, untagged_root, agent_claims)
	category.allow with input as elev_input(tiered_member_role, production_root, agent_claims)
}

# U10 — AGENTS NEVER SEE A CHALLENGE, on a deliberately CONSTRUCTED input: on the rig this token is
# unmintable (the agent clients are ROPC-only, so `act_chain` and `auth_time` cannot coexist), which
# is exactly why the contract is pinned here instead of in a matrix cell. The agent deny is a
# `denied_other`, so the sole-blocker rule suppresses the reason — no TOTP treadmill for a caller
# that cannot TOTP.
test_agent_with_fresh_aal2_denied_and_never_challenged if {
	agent_elevated := object.union(fresh_aal2, agent_claims)
	not category.allow with input as elev_input(tiered_supervisor_role, production_root, agent_elevated)
		with time.now_ns as stepup_now_ns
	not category.deny_reason with input as elev_input(tiered_supervisor_role, production_root, agent_elevated)
		with time.now_ns as stepup_now_ns
}

# Nothing elevation- or agent-related may enter the residual: `filter` still answers TRUE on the very
# inputs the gate denies, so the two are visibly decided in different places (B's pin, re-asserted
# against BOTH of C's new discriminators).
test_elevation_and_agent_never_enter_the_filter_residual if {
	category.filter with input as elev_gate_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
	category.filter with input as elev_gate_input(tiered_supervisor_role, production_root, agent_claims)
}

# …and the defence-in-depth conjunct inside `elevated` itself is asserted DIRECTLY, not only through
# `allow`: the agent deny already closes every path, so a test that went through `allow` would stay
# green if the conjunct were deleted (measured — that mutation caught nothing). Elevation is a human
# ceremony, and this is the rule that says so.
test_elevation_is_a_human_ceremony if {
	not category.elevated with input as elev_input(
		tiered_supervisor_role, production_root,
		object.union(fresh_aal2, agent_claims),
	)
		with time.now_ns as stepup_now_ns
	category.elevated with input as elev_input(tiered_supervisor_role, production_root, fresh_aal2)
		with time.now_ns as stepup_now_ns
}
