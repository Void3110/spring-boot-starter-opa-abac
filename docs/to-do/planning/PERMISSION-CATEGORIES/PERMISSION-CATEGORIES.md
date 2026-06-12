---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/user-service
---

# Permission categories + delegation (Phase 6.5)

> **Status: 🔜 DESIGN SETTLED (2026-06-12) — ready for `/decompose`.** The model is ADR
> [[0007-coarse-grained-permission-categories|0007]]; the ten implementation forks were resolved in
> the 2026-06-12 design interrogation and pinned in [[00-DESIGN]] §2/§7. Stories: [[USER-STORIES]]
> Epic G (G1–G4). Order: 5.97 → **6.5** → 6 ([[POC-ROADMAP]]).

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
| *(produced by `/decompose`)* | — |

## Files

- [[00-DESIGN]] — the settled design (ten forks, behavior matrix, proof obligations)
- `01-DECOMPOSITION` / `10-QA-TEST-CASES` / `AUTONOMOUS-IMPLEMENTATION-PROMPT` / `STATUS-*` — produced by `/decompose`
