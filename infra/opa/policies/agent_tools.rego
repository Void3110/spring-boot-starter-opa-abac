# Agent tool-call authorization — the TOOL-GATE (Phase 9, ADR 0028).
#
# The question this document answers is deliberately NOT the one the per-type documents answer.
# catalog/category/product.rego decide "may this principal act on THIS RESOURCE?" — the target-gate.
# This document decides "may this (principal, actor) pair invoke THIS TOOL AT ALL?" — the tool-gate,
# evaluated by the MCP server BEFORE a tool body runs, with no target resolved and no downstream
# call made.
#
# THE INTERSECTION LIVES HERE. Effective authority = principal ceiling ∩ agent capability, computed
# in Rego so it is auditable and `opa test`-able, never in Java where it could drift from the policy
# that is supposed to own it. The load-bearing property is that the intersection can only NARROW: a
# capability naming an action the principal's ceiling lacks grants nothing (test U22). An agent can
# never do what its principal cannot.
#
# NOTHING IS PROPAGATED. The MCP server sends the catalog service the caller's own bearer and no
# role, capability or acting-as header, so the target-gate independently re-derives the principal
# and applies the same ceiling it would for a direct REST call. The intersection therefore holds
# ACROSS the two layers rather than being passed between them — the caller-supplied-role shape
# slice B4 removed is not reintroduced (ADR 0018, ADR 0028).
#
# ADDITIVE: no existing document is read or changed. This one CONSUMES data.permission_categories
# (the shipped expansion table, ADR 0007) and data.permissions (the shared effective-set math), so
# a principal's ceiling here is derived exactly the way every other enforced path derives it — one
# expansion home, no parallel vocabulary.
#
# INPUT (the shipped AbacContext shape, used additively — see 00-DESIGN §2.2):
#   input.action                                the tool's DECLARED fine verb ("view", "list").
#                                               NOTE: a bare verb, not the "<type>:<verb>" form the
#                                               per-type documents parse — a tool declares a verb
#                                               directly, it does not carry a resource-qualified action.
#   input.resource.type                         "tool"
#   input.resource.id                           the MCP tool name ("get_product")
#   input.resource.attributes.category          the tool's declared permission category ("READ")
#   input.resource.attributes.risk_tags         the tool's declared risk tags (["medium"])
#   input.resource.attributes.target_type       the resource type the tool reads ("product")
#   input.role_definition                       the PRINCIPAL's role definition, resolved as today
#   input.subject.attributes.actor              the agent id — ABSENT for an ordinary human call
#   input.subject.attributes.chain              the ordered delegation chain (audit only; not a grant)
#   input.subject.attributes.agent_capability   {allowed_categories, allowed_tools, allowed_actions,
#                                                max_risk_tag} — ABSENT on an agent call means DENY
#
# OPA 1.x: `if`/`in`/`every`/`some` are built-in keywords — no imports needed. Default deny.

package agent_tools

import data.permissions

default allow := false

# --- guards -------------------------------------------------------------------

# This document decides tool calls and nothing else. A context for any other resource type has no
# business reaching here, and if one does it decides nothing.
is_tool_request if {
	input.resource.type == "tool"
}

# An agent is involved exactly when the delegation extractor put an actor on the subject. Its
# ABSENCE is an ordinary human call (honest, and still ceiling-bounded); a MALFORMED claim never
# reaches this policy at all, because the extractor denies before the PEP builds a context.
is_agent_call if {
	input.subject.attributes.actor
}

# --- tool-declaration integrity -----------------------------------------------

# The tool's declared verb must actually belong to its declared category, per the SHIPPED expansion
# table. This is what makes an unknown category, or a tool whose action and category disagree,
# unauthorizable rather than accidentally authorized by the wrong rule: an unknown category has no
# expansion, so the lookup is undefined and every allow body fails.
declared_action_in_category if {
	input.action in data.permission_categories[input.resource.attributes.category]
}

# --- the principal ceiling ----------------------------------------------------

# The principal's effective fine actions for the type this tool reads — categories expanded through
# data.permission_categories, minus denied_actions (deny-overrides). This is the SAME
# permissions.effective_actions the per-type documents use, so the ceiling the tool-gate enforces is
# by construction the ceiling the target-gate will enforce again downstream.
#
# Total and fail-closed by inheritance: a missing or malformed role_definition expands to the EMPTY
# set (no subject-roles fallback — the boundary slice B4 set), and a missing target_type leaves this
# undefined. Either way nothing is granted.
principal_actions := permissions.effective_actions(input.role_definition, input.resource.attributes.target_type)

# --- the agent capability -----------------------------------------------------

# Undefined when the actor carries no capability profile — which is why an agent call with no
# capability denies rather than running unrestricted.
capability := input.subject.attributes.agent_capability

agent_actions := {action | some action in capability.allowed_actions}

# --- the intersection ---------------------------------------------------------

# THE rule this whole slice exists for. Set intersection can only ever shrink, so a capability
# listing actions the ceiling does not contain contributes nothing: capability NARROWS, never grants.
effective_actions := principal_actions & agent_actions

# --- the agent's other narrowings ---------------------------------------------

# Each is an independent AND, so satisfying one never compensates for failing another.

agent_category_allowed if {
	input.resource.attributes.category in capability.allowed_categories
}

# The allow-list is MANDATORY: a capability must name the tools it may call. An empty list therefore
# denies every tool rather than meaning "unrestricted" — the alternative reading would turn a
# profile someone trimmed to nothing into a profile that permits everything.
agent_tool_allowed if {
	input.resource.id in capability.allowed_tools
}

# Risk ranking. Deliberately a table here rather than an ordering in Java: the comparison that
# decides is the one that should be auditable, and a Java-side ordering would be a second source of
# truth free to drift. An unranked tag on EITHER side (an unknown tool tag, an unknown max) yields
# no rank and the comparison fails -> deny.
risk_rank := {"low": 1, "medium": 2, "high": 3}

# Every declared risk tag must sit at or below the capability's ceiling. The count guard matters:
# `every` over an EMPTY array is vacuously true, so without it a tool declaring no risk tags would
# sail through the risk condition. Registration-time validation already refuses such a tool, and
# this is the same rule enforced independently at the policy layer.
risk_within_capability if {
	count(input.resource.attributes.risk_tags) > 0
	max_rank := risk_rank[capability.max_risk_tag]
	every tag in input.resource.attributes.risk_tags {
		risk_rank[tag] <= max_rank
	}
}

# --- the decisions ------------------------------------------------------------

# HUMAN call — no actor claim. Principal-only: the widest this gate ever goes, and still exactly the
# principal's own ceiling. Honest rather than a bypass, because agent narrowing only ever restricts.
allow if {
	is_tool_request
	not is_agent_call
	declared_action_in_category
	input.action in principal_actions
}

# AGENT call — the intersection, plus the capability's category, tool and risk narrowings.
allow if {
	is_tool_request
	is_agent_call
	declared_action_in_category
	input.action in effective_actions
	agent_category_allowed
	agent_tool_allowed
	risk_within_capability
}

# --- batch entrypoint ---------------------------------------------------------

# The roster pre-flight (`tools/list`): one `allow` per item, positional, in ONE round-trip — the
# same primitive the per-type documents expose for data filtering (ADR 0024). The client POSTs
# {"input": {"items": [<ctx>, …]}} to /v1/data/agent_tools/bulk and reads `result` as a boolean list
# of the same length; a length mismatch fails closed client-side.
#
# ADDITIVE, not a second decision: it maps `allow` over a list. The roster it feeds is a HINT — the
# call-time gate above runs again and is authoritative — so nothing here can widen anything.
bulk := [decision |
	some item in input.items
	decision := allow with input as item
]
