---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/security
  - area/spring
---

# B2 — Supplier-outage fix-slice

> **Status: 🔜 DESIGN SETTLED (grill-me 2026-06-15) — ready for `/decompose`.** Slice **B2** of
> [[POC-ROADMAP]] (route step 1: **B2 → 6.7 → Phase 6 → B3 → Phase 7**). Pinned by
> **ADR [[0014-supplier-outage-error-distinct|0014]]**; the full design is [[00-DESIGN]]. A standalone
> **security** fix kept separate from Phase 6.7's taxonomy work, with its own design + review. The
> resilience follow-up it motivates is the new **Slice B3** (cross-service HTTP resilience, before
> publish).

## What it is

A one-contract security fix at the `RoleDefinitionSupplier` SPI: make a resolve **outage** (the role
source is unavailable → the result is *unknown*) **error-distinct** from an authoritative **no-role**
(the subject genuinely has no role here → a designed signal). Today both collapse to `Optional.empty()`,
so an **outage rides the catalog's realm-role fallback to a grant wider than the resolved role** —
the one tracked widening-on-failure path ([[PERMISSION-CATEGORIES-REVIEW]] C1/C4), aggravated by Phase
6.5 (an outage *erases* the resolved role's `denied_actions`/`required_tags` narrowing).

## The fix in one line

> `lookup(...)` becomes **tri-state**: `Optional.of` = resolved · `Optional.empty()` = authoritative
> no-role (→ realm fallback, **retained**) · **throw `RoleResolutionException`** = outage (→ deny / no
> widening). HTTP: **only `204` → fallback, only `200`+valid → resolved, everything else throws.**

## The pins (from the grill-me; full rationale in [[00-DESIGN]] + ADR 0014)

- **`RoleResolutionException`** — unchecked, in `opa-abac-core` (family-consistent with
  `AncestorResolutionException`; keeps the `@FunctionalInterface` lambda ergonomics).
- **Strict HTTP classification** in `HttpRoleDefinitionSupplier` — stop swallowing; `200`-blank, all
  4xx, 5xx, timeout, connection-refused, malformed-`200` all **throw**.
- **Supplier classifies / each consumer maps** — no library wrapper (it would re-introduce the bug or
  be ceremony; "deny" is non-uniform). The safety is the **explicit contract + a per-consumer test**.
- **All five `lookup()` consumers swept** (the review's "the sweep stopped at the surface the ticket
  named" lesson applied): gate managers → explicit catch → **403** (the only widening path); 
  `HierarchicalAuthorizer` → `false`; `SubtreeSpecResolver` → **no code change** (test-only —
  its existing catch already collapses); `CategoryListAuthorizer` → `Page.empty()`.
- **No kill-switch** — fail-closed is non-optional (the off-ramp would *be* the vulnerability).
- **Zero Rego** — the realm fallback clause is **retained** (load-bearing for non-members and
  type-level creates per 5.97); the outage denies *before* any OPA call. `opa test` **157/157**.
- **`TeamRoleDefinitionSupplier`** (user-mgmt dogfooding) — minimal touch: catch `DataAccessException`
  → throw, so its outage path is legible (outcome unchanged — user-mgmt has no fallback).
- **Logging** — WARN at the supplier throw-site (status / exception class, no PII), DEBUG at the
  consumer catches (uniform-403 means the server log is the only operator-visible outage signal).
- **Resilience out of scope → Slice B3** — retry/backoff/circuit-break across all cross-service HTTP
  edges, before publish; must preserve B2's outage→deny contract.

## Headline proof

An IT: a subject carrying realm `catalog-editor`, **mock supplier throws** (simulated outage), an id'd
write the resolved role would narrow → **403, NOT the widened fallback grant** (the C1/C4 cut). Contrast:
source up + authoritative-`204` → fallback still grants its designed reach. See [[00-DESIGN]] §6.

## Tickets (status table)

| # | Ticket | Module(s) | Status |
|---|--------|-----------|--------|
| **T1** | `RoleResolutionException` + the tri-state SPI contract | core | ✅ |
| **T2** | the two gate managers fail closed on outage *(the behavioral fix)* | spring-security | ✅ |
| **T3** | `HierarchicalAuthorizer` catches; `SubtreeSpecResolver` proven *(hardening)* | spring-data | ✅ |
| **T4** | strict HTTP classification + consumer wrap + conformant showcase | example-catalog, example-user-management | ☐ |
| **T5** | e2e + the headline IT + docs + slice record | example-catalog, docs | ☐ |

**Critical path:** `T1 → {T2, T3, T4} → T5` (T2/T3/T4 independent once T1 lands; **T1+T2** is the
independently-landable subset — the library spine carrying the only widening path). See
[[01-DECOMPOSITION]].

## Files

- [[00-DESIGN]] — the full design (problem, mechanism, behavior matrix, proof obligations, closed forks).
- [[01-DECOMPOSITION]] — the five tickets (Goal / Deliverables / Acceptance / What-NOT-to-touch) + the
  critical path.
- [[10-QA-TEST-CASES]] — the U/I/E/D cases each ticket's *Acceptance* references + the fail-closed
  checklist.
- [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] — the self-contained, checkpoint-gated run prompt.
- `STATUS-01.md … STATUS-05.md` — one per ticket, filled at each checkpoint during the run.
- ADR [[0014-supplier-outage-error-distinct|0014]] — the decision record (every fork + rejections).

## Conventions

- **Clean-room** (root `CLAUDE.md` IP boundary): original neutral names only — every committed word is
  public.
- **Commit identity:** `Void3110 <void31102025@gmail.com>` (repo-local). One focused commit per ticket.
  **No push / PR / `main`** — the maintainer ships (flow phase ④).

## Related

- [[PERMISSION-CATEGORIES-REVIEW]] (C1/C4 — the source finding) · ADR
  [[0013-attribute-rich-pre-authorization|0013]] (the fallback semantics B2 protects) · ADR
  [[0007-coarse-grained-permission-categories|0007]] (the 6.5 narrowing an outage erased).
- [[POC-ROADMAP]] — the route box and the B2/B3 rows.
