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
