---
tags:
  - status/done
  - type/index
  - area/abac
  - area/opa
  - area/user-service
---

# Permission categories + delegation (Phase 6.5)

> **Status: ✅ SHIPPED (2026-06-12)** — implemented T1–T8 by the autonomous run on branch
> `feature/void3110/permission-categories` (per [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]). The model
> is ADR [[0007-coarse-grained-permission-categories|0007]] (+ its Phase-6.5 addendum); the ten
> forks were pinned in [[00-DESIGN]] §2/§7; the work record is the STATUS notes (one per ticket).
> The shipped contract is the guide [[PERMISSION-MODEL]]; the live proof is
> `scripts/postman/run-permission-categories-matrix.sh` (fixture `9999…`, green twice) + the whole
> migrated suite. Stories: [[USER-STORIES]] Epic G (G1–G4 ✅).

**The slice in one sentence**: replace the flat `read`/`write` vocabulary with the four coarse
categories (`READ`/`WRITE`/`TAG`/`GRANT`) expanding to fine actions in OPA `data`, refined by
deny-overrides, bounded by the five-tier level ceiling (incl. the new **senior 25** tier), and
enforce delegation through the two assignment gates — as a **clean cut** (no back-compat; the
starter is unpublished).

## Headline decisions (full record in [[00-DESIGN]])

- Clean cut: the additive-only invariant is consciously waived; stale flat tokens decide nothing.
- Hybrid gates: level compares in Java under the team-row lock; the senior subset verdict via the
  new `data.role.assignable` OPA entrypoint (one expansion source).
- `assign-tags` = a conditional second decision on the tags delta; `define-tags` ships in the math,
  enforcement deferred to the new control-plane slice.
- Seeds migrate in one changelog: senior 25 inserted, `viewer` → `reader`.

## Ticket status

| Ticket | Status |
|---|---|
| T1 — Core: `RoleDefinition.deniedActions` (the flagged build-breaker) | ✅ |
| T2 — Policies: expansion table + `permissions.rego` + the per-type clean cut + PE fold | ✅ |
| T3 — user-mgmt: schema + seed migration (senior 25, `viewer`→`reader`) + resolve wire | ✅ |
| T4 — user-mgmt: the authoring contract (ceiling, category tokens, strict denials) | ✅ |
| T5 — user-mgmt: hybrid assignment gates + `data.role.assignable` + latch-race re-proof | ✅ |
| T6 — catalog: action sweep + the delta-aware `assign-tags` second decision | ✅ |
| T7 — e2e: `run-permission-categories-matrix.sh` (fixture `9999…`) + nine-runner migration | ✅ |
| T8 — docs: `PERMISSION-MODEL.md` guide + reconciliations + stories/roadmap + folder move | ✅ |

**Critical path:** T1 → T3 → T4 → T5 (the user-mgmt chain); T2 independent (before T5/T6);
T6 parallel to T3–T5; T7 needs everything; T8 closes. **No independently-landable subset** —
the clean cut ships whole.

## Files

- [[00-DESIGN]] — the settled design (ten forks, behavior matrix, proof obligations)
- [[01-DECOMPOSITION]] — T1–T8 + the five pinned decomposition semantics + the critical path
- [[10-QA-TEST-CASES]] — U1–U9 · P1–P13 · I1–I16 · E1–E7 · D1–D3 (+ the pinned-contract table)
- [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] — the phase-③ runner prompt (kept verbatim)
- `STATUS-01 … STATUS-08` — one per ticket, filled at each checkpoint

## Conventions

Clean-room (original names only; the verify gate's scan must stay empty) · commit identity
`Void3110 <void31102025@gmail.com>` (repo-local) · one focused commit per ticket · **no push** —
the maintainer pushes/merges.
