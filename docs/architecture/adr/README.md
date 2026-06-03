---
tags:
  - type/index
  - area/abac
  - area/user-service
---

# Architecture Decision Records (ADRs)

> The **why** behind structural decisions, kept as small immutable records. Each ADR captures one
> decision: its context, the options weighed, the choice, and the consequences we accepted. Unlike the
> prose architecture docs ([[DOMAIN-MODEL]], [[TWO-LAYER-AUTHORIZATION]]) which describe how things work
> *now*, an ADR is a dated snapshot of *why a fork was taken* — it doesn't get rewritten as the system
> evolves; it gets **superseded** by a later ADR.

## Format

Lightly-MADR. Each record has: **Status · Context · Decision · Considered options (with why-rejected) ·
Consequences**. Filenames are `NNNN-short-title.md` (zero-padded, monotonic). A decision is never edited
once `Accepted` — to change it, write a new ADR that supersedes it and flip the old one's status.

## Status values

`Proposed` → `Accepted` → (`Superseded by [[NNNN-...]]` | `Deprecated`).

## When to write one (the convention, going forward)

ADRs are **part of the decomposition process**, not an afterthought. When planning a feature
(`docs/to-do/planning/<FEATURE>/`), a **structural decision** gets an ADR — written *up front*, as the
fork is decided, and linked from the feature's `00-DESIGN`. A decision is "structural" when it would be
expensive to reverse or surprising to a future reader: a schema or authority shape, a module/service
boundary, where a check is evaluated (app vs. policy), an additive-vs-breaking choice, a "we deliberately
did **not** do X" with real alternatives weighed.

Why up front, not after: a feature's `00-DESIGN`/`01-DECOMPOSITION` are *living* docs — they get
rewritten and then `git mv`'d to `implemented/` on ship, so the rationale buried in them drifts or moves.
An ADR is *immutable* — it pins the decision and its rejected alternatives at a point in time. Routine
implementation choices (naming, file layout, which test library) do **not** need an ADR; reach for one
only when you catch yourself writing a "considered & rejected" list worth keeping.

> Records 0001–0004 were written **retroactively** for the Phase-4/4.5 user-management work (the decisions
> were sound, the docs just hadn't been pinned). From here, the ADR is authored *with* the decomposition.

## Index

| # | Title | Status | Area |
|---|-------|--------|------|
| [0001](0001-user-management-entity-graph.md) | User-management entity graph & layered service structure | Accepted | user-service |
| [0002](0002-team-and-team-target-indirection.md) | Team + team-target: the resource→authority indirection | Accepted | user-service · abac |
| [0003](0003-role-definitions-role-not-grant.md) | Role definitions: role ≠ grant, system + team-scoped, app-resolved | Accepted | user-service · abac |
| [0004](0004-dynamic-tag-dictionary.md) | The dynamic tag dictionary: three layers, global + team, match-in-Rego | Accepted | user-service · abac |

## Related
- The example app these decisions shape: [[USER-MANAGEMENT-SERVICE]] (Phase 4) and [[TAG-DICTIONARY]] (Phase 4.5).
- The authorization model they feed: [[TEAM-BASED-AUTHORIZATION]], [[TAG-BASED-AUTHORIZATION]], [[ABAC-AUTHORIZATION]].
- The library base they build on: [[DOMAIN-MODEL]].
