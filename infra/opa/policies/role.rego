# Role assignability (Phase 6.5, ADR 0007 — the senior tier's subset-on-effective verdict).
#
# The user-service POSTs {"input": {"actor_role": <raw row>, "candidate_role": <raw row>}} to
# /v1/data/role/assignable. Each raw row carries the STORED shape ({permissions, denied_actions} —
# no resolve-time wildcard expansion), so the shared permissions.effective_actions does the
# wildcard-aware lookup (concrete key wins; "*" backs it up — for grants AND denials).
#
# assignable is true iff, for EVERY type key the candidate grants anything on, the candidate's
# effective fine actions are a subset of the actor's effective fine actions on that same type.
# Set algebra over the SAME expansion the decisions use — a category the actor lacks, an action
# the actor's denial removed, or a "*" candidate against a concrete-keyed actor all fail ⊆.
# A stale/unknown candidate token expands to ∅ and adds nothing (⊆ holds vacuously for it).
#
# FAIL-CLOSED: default false; malformed/missing snapshots (either side not carrying a permissions
# object) never reach the subset walk -> false. The CALLER is fail-closed too (any HTTP error,
# timeout, or missing result -> not assignable), and the Java level gates run BEFORE this verdict.
#
# OPA 1.x: `if`/`in`/`every` are built-in keywords — no imports needed.

package role

import data.permissions

default assignable := false

assignable if {
	is_object(input.actor_role.permissions)
	is_object(input.candidate_role.permissions)
	every type, _ in input.candidate_role.permissions {
		type_covered(type)
	}
}

# The candidate's effective actions for a type are covered by the actor's effective actions on it.
type_covered(type) if {
	candidate := permissions.effective_actions(input.candidate_role, type)
	actor := permissions.effective_actions(input.actor_role, type)
	count(candidate - actor) == 0
}
