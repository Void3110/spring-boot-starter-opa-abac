package team_test

import data.team

# The resolved role definitions the user-service supplies for resource type "team".
# (permissions["team"] is the management capability ladder; see team.rego.)

owner_role_def := {
	"code": "owner",
	"attributes": {"role_level": 40},
	"permissions": {"team": ["read", "manage", "define-roles", "transfer-ownership"]},
}

administrator_role_def := {
	"code": "administrator",
	"attributes": {"role_level": 30},
	"permissions": {"team": ["read", "manage"]},
}

member_role_def := {
	"code": "member",
	"attributes": {"role_level": 20},
	"permissions": {"team": ["read"]},
}

viewer_role_def := {
	"code": "viewer",
	"attributes": {"role_level": 10},
	"permissions": {"team": ["read"]},
}

team_input(action, role_def) := {
	"subject": {"id": "u1", "roles": []},
	"action": action,
	"resource": {"type": "team", "id": "t1"},
	"role_definition": role_def,
	"environment": {},
}

# --- owner: full management ladder ------------------------------------------

test_owner_manages if {
	team.allow with input as team_input("team:manage", owner_role_def)
}

test_owner_transfers_ownership if {
	team.allow with input as team_input("team:transfer-ownership", owner_role_def)
}

test_owner_defines_roles if {
	team.allow with input as team_input("team:define-roles", owner_role_def)
}

# --- administrator: manage, but NOT transfer / roledef:write ----------------

test_administrator_manages if {
	team.allow with input as team_input("team:manage", administrator_role_def)
}

test_administrator_cannot_transfer_ownership if {
	not team.allow with input as team_input("team:transfer-ownership", administrator_role_def)
}

test_administrator_cannot_define_roles if {
	not team.allow with input as team_input("team:define-roles", administrator_role_def)
}

# --- member / viewer: read only, no management ------------------------------

test_member_cannot_manage if {
	not team.allow with input as team_input("team:manage", member_role_def)
}

test_viewer_cannot_manage if {
	not team.allow with input as team_input("team:manage", viewer_role_def)
}

# --- default deny -----------------------------------------------------------

test_default_deny_no_role_definition if {
	not team.allow with input as {
		"subject": {"id": "u9", "roles": []},
		"action": "team:manage",
		"resource": {"type": "team", "id": "t1"},
		"environment": {},
	}
}

test_default_deny_unknown_verb if {
	not team.allow with input as team_input("team:nuke", owner_role_def)
}
