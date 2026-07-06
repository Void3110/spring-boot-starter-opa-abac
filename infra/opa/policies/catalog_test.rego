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

# --- Slice B4: realm-role fallback is now CREATE-ONLY (ADR 0018) -------------------
#
# The blanket realm fallback (viewer→READ, editor→READ+WRITE+TAG on ANY catalog) was REMOVED — it
# leaked every catalog to every authenticated user. The ONLY surviving JWT-role fallback is verb-gated
# to `catalog:create` (type-level onboarding, which precedes any team). Every other verb now requires
# a resolved role_definition (team membership). These cells replace the old reach tests.

fallback_input(roles, action) := {
	"subject": {"id": "u", "roles": roles},
	"action": action,
	"resource": {"type": "catalog", "id": "p1"},
	"environment": {},
}

# R5 — narrow create fallback RETAINED: bare catalog-editor (no role-def) may create.
test_fallback_editor_creates if {
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:create")
}

# R6 — create needs editor: bare catalog-viewer may NOT create.
test_fallback_viewer_cannot_create if {
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:create")
}

# R4 — fallback REMOVED for read/write verbs: bare catalog-editor (no role-def) is denied
# view/list/update/delete/define-tags/assign-tags. (Membership is the sole access path.)
test_fallback_editor_denied_non_create if {
	not catalog.allow with input as fallback_input(["catalog-editor"], "catalog:view")
	not catalog.allow with input as fallback_input(["catalog-editor"], "catalog:list")
	not catalog.allow with input as fallback_input(["catalog-editor"], "catalog:update")
	not catalog.allow with input as fallback_input(["catalog-editor"], "catalog:delete")
	not catalog.allow with input as fallback_input(["catalog-editor"], "catalog:define-tags")
	not catalog.allow with input as fallback_input(["catalog-editor"], "catalog:assign-tags")
	not catalog.allow with input as fallback_input(["catalog-editor"], "catalog:assign-roles")
}

# R4 — bare catalog-viewer is denied everything (it never had create; view/list now also denied).
test_fallback_viewer_denied_everything if {
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:view")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:list")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:update")
	not catalog.allow with input as fallback_input(["catalog-viewer"], "catalog:delete")
}

# A subject with NO realm role and no role-def is denied create too (create needs catalog-editor).
test_fallback_no_role_cannot_create if {
	not catalog.allow with input as fallback_input([], "catalog:create")
	not catalog.allow with input as fallback_input(["random-role"], "catalog:create")
}

# R8 — the resolved-role path is UNCHANGED: a role-def granting READ still allows view.
test_resolved_role_views_unchanged if {
	catalog.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "p1"},
		"role_definition": viewer_role_def,
		"environment": {},
	}
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

# P5 — no role definition at all: the tag conjunct sits only on the role-definition grant path, so
# the (now create-only) realm fallback is unaffected by tags. Slice B4: bare catalog-editor can no
# longer `update` (fallback removed for non-create verbs) regardless of the resource's tags.
test_tag_fallback_path_update_now_denied if {
	not catalog.allow with input as {
		"subject": {"id": "u2", "roles": ["catalog-editor"]},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "c1", "attributes": {"region": "apac"}},
		"environment": {},
	}
}

# --- Slice B4: the role-def-only `filter` entrypoint (R1–R3) ---------------------------
#
# `filter` is the list-isolation gate the app partially-evaluates (resource declared unknown) to get
# the catalog list's base-scope residual. ROLE-DEFINITION-ONLY: no subject-roles fallback, so a
# missing role-def fails closed. These full-eval cells pin the boolean; the *residual shape*
# (ALLOW_ALL `{queries:[[]]}` for R1, DENY_ALL `{}` for R2/R3) is verified empirically with
# `opa eval --partial` in the T1 acceptance run (mx-f63604 — never assume the residual).

filter_input(role_def, subject_roles) := {
	"subject": {"id": "u", "roles": subject_roles},
	"action": "catalog:list",
	"resource": {"type": "catalog"}, # id is the UNKNOWN at partial-eval time
	"role_definition": role_def,
	"environment": {},
}

# R1 — role-def grants `list` (READ expands to view+list) -> filter true (residual ALLOW_ALL).
test_filter_role_def_with_list_grants if {
	catalog.filter with input as filter_input(viewer_role_def, [])
}

# R2 — NO role-def (subject roles only) -> filter false (residual DENY_ALL). The fail-closed boundary:
# the narrow create fallback lives on `allow`, never on `filter`, so a bare realm role lists NOTHING.
test_filter_no_role_def_denies if {
	not catalog.filter with input as {
		"subject": {"id": "u", "roles": ["catalog-editor"]},
		"action": "catalog:list",
		"resource": {"type": "catalog"},
		"environment": {},
	}
}

# R3 — a role that DENIES `list` for catalog -> filter false (deny-overrides at the list boundary).
test_filter_role_denies_list if {
	list_denied_role := {
		"code": "no-list-editor",
		"attributes": {"role_level": 20},
		"permissions": {"catalog": ["READ", "WRITE"]},
		"denied_actions": {"catalog": ["list"]},
	}
	not catalog.filter with input as filter_input(list_denied_role, [])
}

# A role whose catalog permissions do NOT expand to `list` (e.g. only GRANT) -> filter false.
test_filter_role_without_list_denies if {
	grant_only_role := {
		"code": "grant-only",
		"attributes": {"role_level": 40},
		"permissions": {"catalog": ["GRANT"]},
	}
	not catalog.filter with input as filter_input(grant_only_role, [])
}

# --- 7.0.5 fix: filter must honor a TAG-GATED role, exactly as `allow` does (R4–R7) --------------
#
# REGRESSION for the baseline-security-review High: `filter` used to drop the tag conjunct that
# `allow` enforces, so a tag-gated catalog role listed tag-mismatched catalogs that GET /catalogs/{id}
# 403s. These cells pin the boolean with a CONCRETE resource tag (the residual shape — a region
# predicate for a tagged role vs ALLOW_ALL for an untagged one — is verified with `opa eval --partial`
# in the acceptance run, mx-f63604: never assume the residual). `filter_tagged_input` supplies the
# resource attributes that are the UNKNOWN at wire-time PE but are concrete here to pin full-eval.

filter_tagged_input(role_def, attrs) := {
	"subject": {"id": "u", "roles": []},
	"action": "catalog:list",
	"resource": {"type": "catalog", "attributes": attrs},
	"role_definition": role_def,
	"environment": {},
}

emea_role_def := {
	"code": "emea-viewer",
	"attributes": {"role_level": 10},
	"permissions": {"catalog": ["READ"]},
	"required_tags": {"region": ["emea"]},
	"match_mode": "ANY_OF",
}

# R4 — tag-gated (region=emea) role, catalog tagged region=emea -> filter true (the tag matches).
test_filter_tagged_role_matching_tag_lists if {
	catalog.filter with input as filter_tagged_input(emea_role_def, {"region": "emea"})
}

# R5 — SAME tag-gated role, catalog tagged region=apac -> filter FALSE. This is the fix: the list
# path now excludes the mismatched catalog, agreeing with `allow` (which 403s it). Pre-fix this was
# true (the leak).
test_filter_tagged_role_mismatched_tag_denies if {
	not catalog.filter with input as filter_tagged_input(emea_role_def, {"region": "apac"})
}

# R6 — list<->single-GET AGREEMENT on the mismatched catalog: `filter` false AND `allow` false for the
# same role+resource. The invariant the High violated ("list and single-GET agree on visible rows").
test_filter_and_allow_agree_on_mismatched_tag if {
	mismatched := filter_tagged_input(emea_role_def, {"region": "apac"})
	not catalog.filter with input as mismatched
	not catalog.allow with input as object.union(mismatched, {"action": "catalog:view"})
}

# R7 — ALL_OF tag-gated role: filter true only when EVERY required key matches.
test_filter_tagged_role_all_of if {
	all_of_role := {
		"code": "emea-active-viewer",
		"attributes": {"role_level": 10},
		"permissions": {"catalog": ["READ"]},
		"required_tags": {"region": ["emea"], "status": ["active"]},
		"match_mode": "ALL_OF",
	}
	catalog.filter with input as filter_tagged_input(all_of_role, {"region": "emea", "status": "active"})
	not catalog.filter with input as filter_tagged_input(all_of_role, {"region": "emea", "status": "retired"})
}

# --- Phase 5 batch primitive (extended to catalog for Phase 6 action enrichment) ---

# bulk returns a positional list of allow-decisions over input.items (the affordance refold source).
test_bulk_returns_positional_decisions if {
	result := catalog.bulk with input as {"items": [
		{
			"subject": {"id": "u", "roles": []},
			"action": "catalog:view",
			"resource": {"type": "catalog", "id": "a", "attributes": {}},
			"role_definition": viewer_role_def,
			"environment": {},
		},
		{
			"subject": {"id": "u", "roles": []},
			"action": "catalog:update",
			"resource": {"type": "catalog", "id": "a", "attributes": {}},
			"role_definition": viewer_role_def,
			"environment": {},
		},
	]}
	result == [true, false]
}

# bulk over an empty item list -> empty decision list.
test_bulk_empty if {
	result := catalog.bulk with input as {"items": []}
	result == []
}
