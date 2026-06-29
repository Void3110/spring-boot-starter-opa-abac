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
	"attributes": {},
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
			"attributes": {},
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
	"attributes": {},
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
		"attributes": {},
		"permissions": {"catalog": ["READ"]},
		"denied_actions": {"catalog": ["list"]},
	})
		with data.category.inheritable as category_inherits_catalog
}
