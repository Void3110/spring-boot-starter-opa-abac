package agent_tools_test

import data.agent_tools

# Tool-gate tests (Phase 9, ADR 0028) — cases U18–U30 of the slice's QA doc.
#
# The load-bearing one is test_capability_wider_than_ceiling_yields_the_ceiling: it is the single
# assertion the whole agent model rests on. If it ever goes green for the wrong reason, an agent can
# exceed the human it acts for.

# --- fixtures -----------------------------------------------------------------

# A principal who may read AND write products (a broad, realistic ceiling).
principal_role_def := {
	"code": "product-editor",
	"attributes": {"role_level": 20},
	"permissions": {"catalog": ["READ"], "category": ["READ"], "product": ["READ", "WRITE"]},
}

# The same principal, read-only.
reader_role_def := {
	"code": "product-viewer",
	"attributes": {"role_level": 10},
	"permissions": {"catalog": ["READ"], "category": ["READ"], "product": ["READ"]},
}

# A read-only agent capability, narrower than the principal above.
read_capability := {
	"allowed_categories": ["READ"],
	"allowed_tools": ["list_catalogs", "get_catalog", "list_categories", "get_product"],
	"allowed_actions": ["view", "list"],
	"max_risk_tag": "medium",
}

# The authoritative-empty profile — a known agent with zero capability.
empty_capability := {
	"allowed_categories": [],
	"allowed_tools": [],
	"allowed_actions": [],
	"max_risk_tag": "",
}

# get_product: a "view" in category READ, medium risk, targeting products.
product_tool := {
	"type": "tool",
	"id": "get_product",
	"attributes": {"category": "READ", "risk_tags": ["medium"], "target_type": "product"},
}

# list_catalogs: a "list" in category READ, low risk, targeting catalogs.
catalog_tool := {
	"type": "tool",
	"id": "list_catalogs",
	"attributes": {"category": "READ", "risk_tags": ["low"], "target_type": "catalog"},
}

human_subject := {"id": "user-alice", "roles": ["product-editor"]}

agent_subject(capability) := {
	"id": "user-alice",
	"roles": ["product-editor"],
	"attributes": {
		"actor": "agent-a",
		"chain": ["agent-a"],
		"agent_capability": capability,
	},
}

# An agent subject with NO capability profile at all.
agent_subject_without_capability := {
	"id": "user-alice",
	"roles": ["product-editor"],
	"attributes": {"actor": "agent-a", "chain": ["agent-a"]},
}

request(subject, action, resource, role_def) := {
	"subject": subject,
	"action": action,
	"resource": resource,
	"role_definition": role_def,
	"environment": {},
}

# --- U18: the explicit floor ---------------------------------------------------

test_empty_input_denies if {
	not agent_tools.allow with input as {}
}

test_missing_resource_denies if {
	not agent_tools.allow with input as {"subject": human_subject, "action": "view"}
}

# A context for another resource type decides nothing here.
test_non_tool_resource_type_denies if {
	not agent_tools.allow with input as request(
		human_subject, "view",
		{"type": "product", "id": "p-1", "attributes": {"category": "READ", "risk_tags": ["low"], "target_type": "product"}},
		principal_role_def,
	)
}

# --- U19: the human path -------------------------------------------------------

test_human_call_allowed_within_the_ceiling if {
	agent_tools.allow with input as request(human_subject, "view", product_tool, principal_role_def)
}

test_human_call_denied_outside_the_ceiling if {
	# "delete" is a WRITE verb the tool's declared READ category does not contain.
	not agent_tools.allow with input as request(human_subject, "delete", product_tool, principal_role_def)
}

# A human whose role definition does not reach the tool's target type is still denied.
test_human_call_denied_without_a_grant_on_the_target_type if {
	no_product_role := {"code": "catalog-only", "permissions": {"catalog": ["READ"]}}
	not agent_tools.allow with input as request(human_subject, "view", product_tool, no_product_role)
}

# --- an actor of ANY shape stays on the agent branch (deep review 2026-07-31) ---
#
# `is_agent_call` used to be a bare truthiness test, and Rego's only falsy value is `false` — so an
# actor of `false` made the rule undefined and the call was decided by the HUMAN branch, skipping the
# capability conjunct entirely. The principal here is fully permitted `view` on `product`, so the human
# branch ALLOWS: without the presence guard this case returns true with a zero-capability agent.
test_a_boolean_false_actor_is_still_an_agent_call_and_denies if {
	subject := {
		"id": "user-alice",
		"roles": ["product-editor"],
		"attributes": {"actor": false, "agent_capability": empty_capability},
	}
	not agent_tools.allow with input as request(subject, "view", product_tool, principal_role_def)
}

# The sibling shapes, pinned together so the guard can never be "fixed" into a type check that pushes
# an empty or null actor back onto the human branch — that would widen two shapes to narrow one.
test_every_malformed_actor_shape_stays_on_the_agent_branch if {
	every actor in [false, "", 0, null, []] {
		not agent_tools.allow with input as request(
			malformed_actor_subject(actor), "view", product_tool, principal_role_def,
		)
	}
}

malformed_actor_subject(actor) := {
	"id": "user-alice",
	"roles": ["product-editor"],
	"attributes": {"actor": actor, "agent_capability": empty_capability},
}

# --- U20: the agent happy path -------------------------------------------------

test_agent_call_allowed_when_the_intersection_is_non_empty if {
	agent_tools.allow with input as request(agent_subject(read_capability), "view", product_tool, principal_role_def)
}

# --- U21: the narrowing bites --------------------------------------------------

test_agent_denied_when_the_capability_omits_the_category if {
	capability := {
		"allowed_categories": ["TAG"],
		"allowed_tools": ["get_product"],
		"allowed_actions": ["view"],
		"max_risk_tag": "high",
	}
	not agent_tools.allow with input as request(agent_subject(capability), "view", product_tool, principal_role_def)
}

test_agent_denied_when_the_capability_omits_the_action if {
	capability := {
		"allowed_categories": ["READ"],
		"allowed_tools": ["get_product"],
		"allowed_actions": ["list"],
		"max_risk_tag": "high",
	}
	not agent_tools.allow with input as request(agent_subject(capability), "view", product_tool, principal_role_def)
}

# --- U22: THE load-bearing case — capability can only narrow -------------------

# The capability names "delete" (a WRITE verb) and the WRITE category; the principal here is
# READ-ONLY on products. The intersection is what decides, so the agent gets the CEILING, not the
# capability: an agent can never do what its principal cannot.
test_capability_wider_than_ceiling_yields_the_ceiling if {
	wide_capability := {
		"allowed_categories": ["READ", "WRITE"],
		"allowed_tools": ["get_product", "delete_product"],
		"allowed_actions": ["view", "list", "create", "update", "delete"],
		"max_risk_tag": "high",
	}
	write_tool := {
		"type": "tool",
		"id": "delete_product",
		"attributes": {"category": "WRITE", "risk_tags": ["high"], "target_type": "product"},
	}

	# The same agent, same capability: allowed the read it may do...
	agent_tools.allow with input as request(agent_subject(wide_capability), "view", product_tool, reader_role_def)

	# ...and denied the write its capability lists, because the PRINCIPAL cannot write.
	not agent_tools.allow with input as request(agent_subject(wide_capability), "delete", write_tool, reader_role_def)
}

# The contrast that proves the previous test is not passing for an unrelated reason: give the SAME
# agent a principal who CAN write, and the very same call is allowed.
test_the_same_write_is_allowed_when_the_principal_can_write if {
	wide_capability := {
		"allowed_categories": ["READ", "WRITE"],
		"allowed_tools": ["delete_product"],
		"allowed_actions": ["delete"],
		"max_risk_tag": "high",
	}
	write_tool := {
		"type": "tool",
		"id": "delete_product",
		"attributes": {"category": "WRITE", "risk_tags": ["high"], "target_type": "product"},
	}
	agent_tools.allow with input as request(agent_subject(wide_capability), "delete", write_tool, principal_role_def)
}

# --- U23: risk is independent of category --------------------------------------

test_agent_denied_when_a_risk_tag_exceeds_the_maximum if {
	low_risk_capability := {
		"allowed_categories": ["READ"],
		"allowed_tools": ["get_product", "list_catalogs"],
		"allowed_actions": ["view", "list"],
		"max_risk_tag": "low",
	}

	# The low-risk tool passes...
	agent_tools.allow with input as request(agent_subject(low_risk_capability), "list", catalog_tool, principal_role_def)

	# ...and the medium-risk one does not, with category and action otherwise satisfied.
	not agent_tools.allow with input as request(agent_subject(low_risk_capability), "view", product_tool, principal_role_def)
}

test_agent_denied_when_any_of_several_risk_tags_exceeds_the_maximum if {
	capability := {
		"allowed_categories": ["READ"],
		"allowed_tools": ["get_product"],
		"allowed_actions": ["view"],
		"max_risk_tag": "medium",
	}
	two_tag_tool := {
		"type": "tool",
		"id": "get_product",
		"attributes": {"category": "READ", "risk_tags": ["low", "high"], "target_type": "product"},
	}
	not agent_tools.allow with input as request(agent_subject(capability), "view", two_tag_tool, principal_role_def)
}

# An unknown risk tag has no rank — it cannot be compared, so it denies.
test_unknown_risk_tag_denies if {
	unranked_tool := {
		"type": "tool",
		"id": "get_product",
		"attributes": {"category": "READ", "risk_tags": ["catastrophic"], "target_type": "product"},
	}
	not agent_tools.allow with input as request(agent_subject(read_capability), "view", unranked_tool, principal_role_def)
}

test_unknown_max_risk_tag_denies if {
	capability := {
		"allowed_categories": ["READ"],
		"allowed_tools": ["get_product"],
		"allowed_actions": ["view"],
		"max_risk_tag": "unbounded",
	}
	not agent_tools.allow with input as request(agent_subject(capability), "view", product_tool, principal_role_def)
}

# `every` over an empty array is vacuously true — the count guard is what stops a tool declaring no
# risk tags from sailing through the risk condition.
test_tool_with_no_risk_tags_denies if {
	unclassified_tool := {
		"type": "tool",
		"id": "get_product",
		"attributes": {"category": "READ", "risk_tags": [], "target_type": "product"},
	}
	not agent_tools.allow with input as request(agent_subject(read_capability), "view", unclassified_tool, principal_role_def)
}

# --- U24: the tool allow-list is a further narrowing ---------------------------

test_agent_denied_when_the_tool_is_absent_from_the_allow_list if {
	capability := {
		"allowed_categories": ["READ"],
		"allowed_tools": ["list_catalogs"],
		"allowed_actions": ["view", "list"],
		"max_risk_tag": "high",
	}

	# Category, action and risk all satisfied — and it still denies, because the tool is not listed.
	not agent_tools.allow with input as request(agent_subject(capability), "view", product_tool, principal_role_def)
}

# --- U25: authoritative-empty -------------------------------------------------

test_empty_capability_denies_every_tool if {
	not agent_tools.allow with input as request(agent_subject(empty_capability), "view", product_tool, principal_role_def)
	not agent_tools.allow with input as request(agent_subject(empty_capability), "list", catalog_tool, principal_role_def)
}

# --- U26: an agent with no capability at all -----------------------------------

test_agent_without_a_capability_profile_denies if {
	not agent_tools.allow with input as request(agent_subject_without_capability, "view", product_tool, principal_role_def)
}

# --- U27: no ceiling, no grant -------------------------------------------------

test_missing_role_definition_denies_for_a_human if {
	not agent_tools.allow with input as {
		"subject": human_subject,
		"action": "view",
		"resource": product_tool,
		"environment": {},
	}
}

test_missing_role_definition_denies_for_an_agent if {
	not agent_tools.allow with input as {
		"subject": agent_subject(read_capability),
		"action": "view",
		"resource": product_tool,
		"environment": {},
	}
}

# Subject roles alone are not a fallback — the boundary slice B4 set.
test_subject_roles_alone_do_not_grant if {
	not agent_tools.allow with input as request(agent_subject(read_capability), "view", product_tool, {})
}

# A denied_actions entry subtracts after expansion (deny-overrides carries through the ceiling).
test_denied_action_narrows_the_ceiling if {
	denied_role_def := {
		"code": "product-viewer-no-view",
		"permissions": {"product": ["READ"]},
		"denied_actions": {"product": ["view"]},
	}
	not agent_tools.allow with input as request(agent_subject(read_capability), "view", product_tool, denied_role_def)
}

# --- U28: unknown category / verb outside the vocabulary -----------------------

test_unknown_category_denies if {
	unknown_category_tool := {
		"type": "tool",
		"id": "get_product",
		"attributes": {"category": "SUPERUSER", "risk_tags": ["low"], "target_type": "product"},
	}
	not agent_tools.allow with input as request(agent_subject(read_capability), "view", unknown_category_tool, principal_role_def)
}

test_action_outside_the_declared_category_denies if {
	# "create" is a WRITE verb; the tool declares READ. The declaration is internally inconsistent,
	# so it is unauthorizable rather than authorized by the wrong rule.
	not agent_tools.allow with input as request(agent_subject(read_capability), "create", product_tool, principal_role_def)
}

test_unknown_verb_denies if {
	not agent_tools.allow with input as request(agent_subject(read_capability), "exfiltrate", product_tool, principal_role_def)
}

test_missing_target_type_denies if {
	untargeted_tool := {
		"type": "tool",
		"id": "get_product",
		"attributes": {"category": "READ", "risk_tags": ["low"]},
	}
	not agent_tools.allow with input as request(agent_subject(read_capability), "view", untargeted_tool, principal_role_def)
}

# --- U29: the batch primitive --------------------------------------------------

test_bulk_is_positional_and_order_preserving if {
	low_risk_capability := {
		"allowed_categories": ["READ"],
		"allowed_tools": ["list_catalogs"],
		"allowed_actions": ["view", "list"],
		"max_risk_tag": "low",
	}
	subject := agent_subject(low_risk_capability)
	decisions := agent_tools.bulk with input as {"items": [
		request(subject, "list", catalog_tool, principal_role_def),
		request(subject, "view", product_tool, principal_role_def),
		request(subject, "list", catalog_tool, principal_role_def),
	]}

	# Exactly the expected true/false vector, in request order.
	decisions == [true, false, true]
}

test_bulk_all_false_for_a_zero_capability_agent if {
	subject := agent_subject(empty_capability)
	decisions := agent_tools.bulk with input as {"items": [
		request(subject, "list", catalog_tool, principal_role_def),
		request(subject, "view", product_tool, principal_role_def),
	]}

	decisions == [false, false]
}

test_bulk_of_no_items_is_empty if {
	decisions := agent_tools.bulk with input as {"items": []}
	decisions == []
}

# --- delegation-chain depth is audit data, never a grant -----------------------

test_a_longer_chain_does_not_widen if {
	subject := {
		"id": "user-alice",
		"roles": ["product-editor"],
		"attributes": {
			"actor": "agent-a",
			"chain": ["agent-a", "agent-b", "agent-c"],
			"agent_capability": read_capability,
		},
	}

	# Same decision as the single-hop agent: the chain is recorded, not consulted.
	agent_tools.allow with input as request(subject, "view", product_tool, principal_role_def)
}
