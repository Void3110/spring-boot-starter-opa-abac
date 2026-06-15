package team_test

import data.team

# Phase 6.7 — team.rego is now CATEGORY-DRIVEN (ADR 0015), symmetric with catalog.rego: the resolved
# management role's permissions["team"] carries COARSE category tokens that expand through the ONE
# shared data.permission_categories table; the two owner-only verbs (define-roles, transfer-ownership)
# are an owner-only-by-code fence OUTSIDE the category system. These tests run against the REAL
# permission_categories.json (colocated in this bundle), so the expansion is the production math.
#
# The capability ladder (the Java TeamRoleCapabilities projection of the role CODE):
#   owner / administrator -> [READ, CONTROL, TAG]
#   senior                -> [READ, CONTROL]   (no TAG)
#   member / reader / custom -> [READ]

owner_role_def := {
	"code": "owner",
	"attributes": {"role_level": 40},
	"permissions": {"team": ["READ", "CONTROL", "TAG"]},
}

administrator_role_def := {
	"code": "administrator",
	"attributes": {"role_level": 30},
	"permissions": {"team": ["READ", "CONTROL", "TAG"]},
}

senior_role_def := {
	"code": "senior",
	"attributes": {"role_level": 25},
	"permissions": {"team": ["READ", "CONTROL"]},
}

member_role_def := {
	"code": "member",
	"attributes": {"role_level": 20},
	"permissions": {"team": ["READ"]},
}

reader_role_def := {
	"code": "reader",
	"attributes": {"role_level": 10},
	"permissions": {"team": ["READ"]},
}

# A custom (team-scoped) role — projected to [READ] by TeamRoleCapabilities. Even if one carried a
# CONTROL token (authoring now 422s that), the owner-only fence still keys on the "owner" CODE, not
# the level — so a custom level-30 "lead" can never reach define-roles/transfer-ownership.
custom_lead_role_def := {
	"code": "lead",
	"attributes": {"role_level": 30},
	"permissions": {"team": ["CONTROL"]},
}

team_input(action, role_def) := {
	"subject": {"id": "u1", "roles": []},
	"action": action,
	"resource": {"type": "team", "id": "t1"},
	"role_definition": role_def,
	"environment": {},
}

# --- R5/R6: owner & administrator carry TAG -> define-tags --------------------

test_owner_defines_tags if {
	team.allow with input as team_input("team:define-tags", owner_role_def)
}

test_administrator_defines_tags if {
	team.allow with input as team_input("team:define-tags", administrator_role_def)
}

# --- R7: senior carries CONTROL but NOT TAG -> NO define-tags (pinned #3) -----

test_senior_cannot_define_tags if {
	not team.allow with input as team_input("team:define-tags", senior_role_def)
}

# --- R8: CONTROL grants the three membership verbs (owner/admin/senior) -------

test_owner_adds_member if {
	team.allow with input as team_input("team:add-member", owner_role_def)
}

test_owner_changes_role if {
	team.allow with input as team_input("team:change-role", owner_role_def)
}

test_owner_removes_member if {
	team.allow with input as team_input("team:remove-member", owner_role_def)
}

test_administrator_manages_members if {
	team.allow with input as team_input("team:add-member", administrator_role_def)
	team.allow with input as team_input("team:change-role", administrator_role_def)
	team.allow with input as team_input("team:remove-member", administrator_role_def)
}

test_senior_manages_members if {
	team.allow with input as team_input("team:add-member", senior_role_def)
	team.allow with input as team_input("team:change-role", senior_role_def)
	team.allow with input as team_input("team:remove-member", senior_role_def)
}

# --- R9: list-members rides READ (the loosening) — any READ-holder lists; -----
# --- a READ-only role gets NOTHING wider (every mutation still denies) --------

test_owner_lists_members if {
	team.allow with input as team_input("team:list-members", owner_role_def)
}

test_member_lists_members if {
	team.allow with input as team_input("team:list-members", member_role_def)
}

test_reader_lists_members if {
	team.allow with input as team_input("team:list-members", reader_role_def)
}

test_member_cannot_add_member if {
	not team.allow with input as team_input("team:add-member", member_role_def)
}

test_member_cannot_change_role if {
	not team.allow with input as team_input("team:change-role", member_role_def)
}

test_member_cannot_remove_member if {
	not team.allow with input as team_input("team:remove-member", member_role_def)
}

test_member_cannot_define_tags if {
	not team.allow with input as team_input("team:define-tags", member_role_def)
}

test_reader_cannot_add_member if {
	not team.allow with input as team_input("team:add-member", reader_role_def)
}

# --- R10: the owner-only fence — define-roles / transfer-ownership ------------
# --- allowed ONLY for code == "owner"; never for administrator/senior/member --

test_owner_defines_roles if {
	team.allow with input as team_input("team:define-roles", owner_role_def)
}

test_owner_transfers_ownership if {
	team.allow with input as team_input("team:transfer-ownership", owner_role_def)
}

test_administrator_cannot_define_roles if {
	not team.allow with input as team_input("team:define-roles", administrator_role_def)
}

test_administrator_cannot_transfer_ownership if {
	not team.allow with input as team_input("team:transfer-ownership", administrator_role_def)
}

test_senior_cannot_define_roles if {
	not team.allow with input as team_input("team:define-roles", senior_role_def)
}

test_senior_cannot_transfer_ownership if {
	not team.allow with input as team_input("team:transfer-ownership", senior_role_def)
}

test_member_cannot_define_roles if {
	not team.allow with input as team_input("team:define-roles", member_role_def)
}

# --- R11: the fence is keyed on the owner CODE, not role_level — a custom -----
# --- level-30 "lead" (even carrying CONTROL) can NEVER reach the two verbs ----

test_custom_lead_cannot_define_roles if {
	not team.allow with input as team_input("team:define-roles", custom_lead_role_def)
}

test_custom_lead_cannot_transfer_ownership if {
	not team.allow with input as team_input("team:transfer-ownership", custom_lead_role_def)
}

# --- deny-override on CONTROL: grant CONTROL, deny remove-member -> denied -----

test_control_deny_override_blocks_remove if {
	role_def := {
		"code": "administrator",
		"attributes": {"role_level": 30},
		"permissions": {"team": ["READ", "CONTROL", "TAG"]},
		"denied_actions": {"team": ["remove-member"]},
	}
	team.allow with input as team_input("team:add-member", role_def) # still allowed
	not team.allow with input as team_input("team:remove-member", role_def) # subtracted
}

# --- R12: default deny — unknown verb / no role_definition / empty token list --

test_default_deny_unknown_verb if {
	not team.allow with input as team_input("team:nuke", owner_role_def)
}

test_default_deny_stale_manage_verb if {
	# the retired coarse "manage" verb is no longer a fine action — it expands to nothing.
	not team.allow with input as team_input("team:manage", owner_role_def)
}

test_default_deny_no_role_definition if {
	not team.allow with input as {
		"subject": {"id": "u9", "roles": []},
		"action": "team:add-member",
		"resource": {"type": "team", "id": "t1"},
		"environment": {},
	}
}

test_default_deny_empty_team_tokens if {
	not team.allow with input as team_input("team:list-members", {
		"code": "member",
		"attributes": {"role_level": 20},
		"permissions": {"team": []},
	})
}
