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

# --- deny-overrides on the ROOT type (7.4 security review, catalog.rego High) ---
# `abac_deny` on the resource vetoes any single-GET grant, matching category.rego/product.rego,
# so single-GET agrees with the list side (which excludes the row via the SQL notDenied residual)
# — the ADR-0010 §3 invariant on the root type. The baseline (no deny) allowing is the same
# fixture minus the flag: test_editor_role_def_views / _updates above prove the grant fires.

test_abac_deny_vetoes_view if {
	not catalog.allow with input as {
		"subject": {"id": "u1", "roles": []},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "p1", "attributes": {"abac_deny": true}},
		"role_definition": viewer_role_def,
		"environment": {},
	}
}

test_abac_deny_vetoes_update if {
	not catalog.allow with input as {
		"subject": {"id": "u2", "roles": []},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "p1", "attributes": {"abac_deny": true}},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# the dangerous direction: DELETE must also be vetoed by the operator flag.
test_abac_deny_vetoes_delete if {
	not catalog.allow with input as {
		"subject": {"id": "u2", "roles": []},
		"action": "catalog:delete",
		"resource": {"type": "catalog", "id": "p1", "attributes": {"abac_deny": true}},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# list↔single-GET agreement: with the SAME editor role, `catalog:view` on a plain catalog is
# ALLOWED but on the abac_deny-flagged catalog is DENIED — the two paths now agree on "denied".
test_abac_deny_view_agrees_with_list if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": []},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "plain"},
		"role_definition": editor_role_def,
		"environment": {},
	}
	not catalog.allow with input as {
		"subject": {"id": "u2", "roles": []},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "denied", "attributes": {"abac_deny": true}},
		"role_definition": editor_role_def,
		"environment": {},
	}
}

# abac_deny=false / absent must NOT deny (the veto is strictly on the true flag, no over-denial).
test_abac_deny_false_still_allows if {
	catalog.allow with input as {
		"subject": {"id": "u2", "roles": []},
		"action": "catalog:view",
		"resource": {"type": "catalog", "id": "p1", "attributes": {"abac_deny": false}},
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

# R5 — SAME tag-gated role, catalog tagged region=apac -> filter FALSE in STRICT mode. This is the
# 7.0.5 fix: the list path excludes the mismatched catalog, agreeing with `allow` (which 403s it).
# Pre-fix this was true (the leak). Strict mode is pinned explicitly (`with data.config as {}`)
# because under the ROOT-READ EXEMPTION (ADR 0022 — the shipped config.json default) both list AND
# view pass instead; the 7.0.5 invariant is list<->GET AGREEMENT, never one-sided strictness — the
# exemption-mode agreement is pinned by the E-cells below.
test_filter_tagged_role_mismatched_tag_denies if {
	not catalog.filter with input as filter_tagged_input(emea_role_def, {"region": "apac"})
		with data.config as {}
}

# R6 — list<->single-GET AGREEMENT on the mismatched catalog in STRICT mode: `filter` false AND
# `allow` false for the same role+resource. (Exemption-mode agreement: E4.)
test_filter_and_allow_agree_on_mismatched_tag if {
	mismatched := filter_tagged_input(emea_role_def, {"region": "apac"})
	not catalog.filter with input as mismatched with data.config as {}
	not catalog.allow with input as object.union(mismatched, {"action": "catalog:view"})
		with data.config as {}
}

# R7 — ALL_OF tag-gated role in STRICT mode: filter true only when EVERY required key matches.
test_filter_tagged_role_all_of if {
	all_of_role := {
		"code": "emea-active-viewer",
		"attributes": {"role_level": 10},
		"permissions": {"catalog": ["READ"]},
		"required_tags": {"region": ["emea"], "status": ["active"]},
		"match_mode": "ALL_OF",
	}
	catalog.filter with input as filter_tagged_input(all_of_role, {"region": "emea", "status": "active"})
		with data.config as {}
	not catalog.filter with input as filter_tagged_input(all_of_role, {"region": "emea", "status": "retired"})
		with data.config as {}
}

# --- ADR 0022: the ROOT-READ TAG EXEMPTION (E1–E6) -----------------------------------------------
#
# The catalog is the governing root — the resource the team-target explicitly names — so READ-level
# verbs (view/list) on it are exempt from required_tags when data.config.root_read_tag_exemption is
# true (the shipped config.json default; `opa test` loads it from this directory, so the E-cells run
# against the REAL default). Mutations on the root and everything below stay tag-gated. An absent
# flag means STRICT — the widening is an explicit deployment choice, never a missing-file default.

# E1 — exemption ON (dir default): tag-gated role VIEWS the untagged catalog (the membership grant
# on the named root is not voided by the role's tag filter — the alice lockout, fixed).
test_exempt_root_view_untagged_catalog if {
	catalog.allow with input as object.union(
		catalog_tag_input(regional_catalog_writer, {}),
		{"action": "catalog:view"},
	)
}

# E2 — exemption ON: the same role LISTS (filter true — the tag conjunct drops from the residual).
test_exempt_root_list_untagged_catalog if {
	catalog.filter with input as filter_tagged_input(emea_role_def, {})
}

# E3 — exemption ON: mutations on the root stay tag-gated — the untagged catalog still denies
# update for the tag-gated role (the exemption is READ-only; catalog tags gate mutations).
test_exempt_root_update_still_tag_gated if {
	not catalog.allow with input as catalog_tag_input(regional_catalog_writer, {})
}

# E4 — exemption ON: list<->single-GET AGREEMENT on the mismatched catalog — BOTH pass (the
# exemption-mode counterpart of R6; agreement holds in both flag states).
test_exempt_filter_and_allow_agree if {
	mismatched := filter_tagged_input(emea_role_def, {"region": "apac"})
	catalog.filter with input as mismatched
	catalog.allow with input as object.union(mismatched, {"action": "catalog:view"})
}

# E5 — flag ABSENT -> STRICT: the untagged catalog view denies (fail-closed on missing config;
# the exemption never activates by omission).
test_absent_flag_is_strict if {
	not catalog.allow with input as object.union(
		catalog_tag_input(regional_catalog_writer, {}),
		{"action": "catalog:view"},
	)
		with data.config as {}
}

# E6 — flag EXPLICITLY false -> STRICT (the deploy.sh toggle's off state).
test_false_flag_is_strict if {
	not catalog.allow with input as object.union(
		catalog_tag_input(regional_catalog_writer, {}),
		{"action": "catalog:view"},
	)
		with data.config as {"root_read_tag_exemption": false}
	not catalog.filter with input as filter_tagged_input(emea_role_def, {})
		with data.config as {"root_read_tag_exemption": false}
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

# --- THE SUPERVISED PATH IS HUMAN-ONLY (ADR 0030 Amendment 4, slice C) — U9 --------------------
#
# The root type carries no tier (that lives on the children, category.rego/product.rego), so this
# file gets ONE new clause: the provenance-scoped agent deny. No `elevated`, no `deny_reason` — a
# catalog read is metadata-only and is never the sensitive act.

supervised_catalog_role := {
	"code": "supervisor-readonly",
	"attributes": {"provenance": "supervised"},
	"permissions": {"catalog": ["READ"]},
}

member_catalog_role := {
	"code": "catalog-owner",
	"attributes": {"provenance": "membership"},
	"permissions": {"catalog": ["READ"]},
}

agent_catalog_input(role_def, subject_attrs) := {
	"subject": {"id": "u1", "roles": [], "attributes": subject_attrs},
	"action": "catalog:view",
	"resource": {"type": "catalog", "id": "c1", "attributes": {}},
	"role_definition": role_def,
	"environment": {},
}

# The control: the same supervised read WITHOUT the delegation claim is allowed, so the deny below
# is the claim's doing and not the fixture's.
test_supervised_human_catalog_read_allowed if {
	catalog.allow with input as agent_catalog_input(supervised_catalog_role, {})
}

test_supervised_agent_catalog_read_denied if {
	not catalog.allow with input as agent_catalog_input(supervised_catalog_role, {"act_chain": ["agent-readonly"]})
}

# THE RECORDED ESCAPE: a bare truthiness test would let `act_chain: false` through to the human
# branch. Every falsy/empty shape must still be an agent call.
test_agent_presence_test_survives_falsy_claim_values if {
	every value in [false, [], "", 0, null] {
		not catalog.allow with input as agent_catalog_input(supervised_catalog_role, {"act_chain": value})
	}
}

# A MEMBER's agent call is untouched — the clause is provenance-scoped, so the agent surface's
# existing member behaviour (ADR 0028) cannot be reached from here.
test_member_agent_catalog_read_unaffected if {
	catalog.allow with input as agent_catalog_input(member_catalog_role, {"act_chain": ["agent-readonly"]})
}

# The agent discriminator never enters the residual: `filter` does not consult `denied`, so the list
# leg's agent closure is the app's job (CatalogListAuthorizer, T4) and visibly not the policy's.
test_agent_never_enters_the_filter_residual if {
	catalog.filter with input as object.union(
		agent_catalog_input(supervised_catalog_role, {"act_chain": ["agent-readonly"]}),
		{"action": "catalog:list", "resource": {"type": "catalog", "attributes": {}}},
	)
}

# --- boolean-valued attributes are PRESENT keys (the truthiness trap, 2026-08-24) ----

# A `false`-valued attribute for a required tag key must land on the ordinary no-match deny —
# never on a resource_tag_values eval conflict (a truthiness-guarded fallback also fires on
# `false`; two outputs = the data API's HTTP 500). The direct helper pin makes the
# singleton-clause routing explicit; before the key-presence guard this test ERRORED, not failed.
test_tag_boolean_false_attribute_denies_without_conflict if {
	not catalog.allow with input as catalog_tag_input(regional_catalog_writer, {"region": false})
	catalog.resource_tag_values("region") == {false}
		with input as catalog_tag_input(regional_catalog_writer, {"region": false})
}

# The LIST-path sibling of the denial shape guard (deep review 2026-08-24, round 2): a malformed
# consulted denial value must fail the residual closed — `in` over a non-collection is undefined,
# so without the second filter_list_denied clause the residual would be WIDER than the decision.
test_filter_malformed_denial_fails_closed if {
	well_formed := {
		"code": "r",
		"attributes": {"role_level": 15},
		"permissions": {"catalog": ["READ"]},
	}
	catalog.filter with input as {
		"subject": {"id": "u", "roles": []},
		"action": "catalog:list",
		"resource": {"type": "catalog"},
		"role_definition": well_formed,
		"environment": {},
	}
	not catalog.filter with input as {
		"subject": {"id": "u", "roles": []},
		"action": "catalog:list",
		"resource": {"type": "catalog"},
		"role_definition": object.union(well_formed, {"denied_actions": {"catalog": false}}),
		"environment": {},
	}
}

# --- round-3 pins (deep review 2026-08-24): the remaining malformed-shape escapes ----

# (a) A NON-OBJECT denied_actions map fails the LIST residual closed — the decision side
# already collapses via denied_for's object guards; the residual must not be wider.
test_filter_nonobject_denied_actions_fails_closed if {
	not catalog.filter with input as {
		"subject": {"id": "u", "roles": []},
		"action": "catalog:list",
		"resource": {"type": "catalog"},
		"role_definition": {
			"code": "r",
			"attributes": {"role_level": 15},
			"permissions": {"catalog": ["READ"]},
			"denied_actions": "corrupt",
		},
		"environment": {},
	}
}

# (b) A present NON-COLLECTION required_tags is a requirement nothing satisfies: the configured
# narrowing must never silently drop (count() type-errored to undefined and the vacuous clause
# passed; has_required_tags is now presence-keyed). Decision AND list, with a positive baseline.
test_malformed_required_tags_scalar_denies if {
	base := {
		"code": "r",
		"attributes": {"role_level": 15},
		"permissions": {"catalog": ["READ", "WRITE"]},
	}
	full_input := {
		"subject": {"id": "u", "roles": []},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "x1", "attributes": {}},
		"environment": {},
	}
	catalog.allow with input as object.union(full_input, {"role_definition": base})
	not catalog.allow with input as object.union(
		full_input,
		{"role_definition": object.union(base, {"required_tags": false})},
	)
	not catalog.allow with input as object.union(
		full_input,
		{"role_definition": object.union(base, {"required_tags": 123})},
	)
	not catalog.filter with input as {
		"subject": {"id": "u", "roles": []},
		"action": "catalog:list",
		"resource": {"type": "catalog"},
		"role_definition": object.union(base, {"required_tags": false}),
		"environment": {},
	}
		with data.config as {}
}

# Round-4 pin (deep review 2026-08-24): the create fallback opens ONLY for an ABSENT
# role_definition — a PRESENT one with a malformed permissions value (false: the recorded
# single-value escape) must not read as absent. has_role_definition is presence-keyed; the
# old truthiness guard reopened the realm branch for exactly this shape.
test_fallback_closed_for_present_malformed_role_definition if {
	catalog.allow with input as fallback_input(["catalog-editor"], "catalog:create")
	not catalog.allow with input as object.union(
		fallback_input(["catalog-editor"], "catalog:create"),
		{"role_definition": {"permissions": false}},
	)
}

# Round-4 pins: a present EMPTY collection (object or array) is "no requirement" in BOTH match
# modes (back-compat with the count()>0 reading — [] + ALL_OF would otherwise pass only via a
# vacuous `every`, and [] + ANY_OF silently flipped to deny); a present NON-EMPTY array is a
# requirement nothing satisfies.
test_empty_required_tags_collections_are_no_requirement if {
	base := {
		"code": "r",
		"attributes": {"role_level": 15},
		"permissions": {"catalog": ["READ", "WRITE"]},
	}
	upd := {
		"subject": {"id": "u", "roles": []},
		"action": "catalog:update",
		"resource": {"type": "catalog", "id": "x1", "attributes": {}},
		"environment": {},
	}
	catalog.allow with input as object.union(
		upd,
		{"role_definition": object.union(base, {"required_tags": {}, "match_mode": "ALL_OF"})},
	)
	catalog.allow with input as object.union(
		upd,
		{"role_definition": object.union(base, {"required_tags": [], "match_mode": "ALL_OF"})},
	)
	catalog.allow with input as object.union(
		upd,
		{"role_definition": object.union(base, {"required_tags": [], "match_mode": "ANY_OF"})},
	)
	not catalog.allow with input as object.union(
		upd,
		{"role_definition": object.union(base, {"required_tags": ["region"], "match_mode": "ANY_OF"})},
	)
	not catalog.allow with input as object.union(
		upd,
		{"role_definition": object.union(base, {"required_tags": ["region"], "match_mode": "ALL_OF"})},
	)
}

# Round-5 pin: a present NON-OBJECT role_definition also blocks the create fallback
# (present-but-malformed never reads as absent); an explicit null stays honestly absent.
test_fallback_closed_for_nonobject_role_definition if {
	not catalog.allow with input as object.union(
		fallback_input(["catalog-editor"], "catalog:create"),
		{"role_definition": "corrupt"},
	)
	catalog.allow with input as object.union(
		fallback_input(["catalog-editor"], "catalog:create"),
		{"role_definition": null},
	)
}

# Round-5 mirror of category's decision-level malformed-denial witness (sibling cell parity):
# the same input that allows under a well-formed role must deny outright when the consulted
# denial value is malformed — never widen by dropping the "*" subtraction.
catalog_malformed_denial_writer := object.union(
	regional_catalog_writer,
	{"denied_actions": {"*": ["delete"], "catalog": false}},
)

test_malformed_denial_in_role_definition_denies_at_the_decision if {
	catalog.allow with input as catalog_tag_input(regional_catalog_writer, {"region": "emea"})
	not catalog.allow with input as catalog_tag_input(catalog_malformed_denial_writer, {"region": "emea"})
}

# Round-6 pin: a present OBJECT role_definition WITHOUT a permissions key (a role carrying
# only denials) also blocks the create fallback — ANY present non-null document does; the
# fallback path never consults denied_actions, so reading such a role as absent would
# silently drop its explicit denial.
test_fallback_closed_for_denials_only_role_definition if {
	not catalog.allow with input as object.union(
		fallback_input(["catalog-editor"], "catalog:create"),
		{"role_definition": {"denied_actions": {"catalog": ["create"]}}},
	)
}
