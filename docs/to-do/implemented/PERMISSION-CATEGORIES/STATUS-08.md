---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# STATUS — T8: docs + the slice record

**Status:** ✅ DONE (2026-06-12)

## What shipped

- **New guide `docs/guides/PERMISSION-MODEL.md`** — the shipped contract: the four categories +
  the expansion table (and that OPA `data` is the one runtime home, the Java table
  validation-only/parity-pinned), deny-overrides **with the per-type/inheritance subtlety from
  the T7 investigation** (a leaf-type denial does not veto an ancestor-type grant — fence
  subtree-wide via every granted type or `"*"`), the five-tier ladder + ceilings, the authoring
  contract (the uniform 422), the two assignment gates + `data.role.assignable` (OPA-failure
  rejects; one rejection shape; tier-vs-ceiling), the delta-dispatched second decision (+ the
  404/403 missing-id note), the ∅-expansion floor, the `define-tags` enforcement-deferral, and
  the file map.
- **Six reconciliations**: [[ABAC-AUTHORIZATION]] (category vocabulary, `product:update` example,
  effective-actions decision shape), [[TEAM-BASED-AUTHORIZATION]] (rule 2 → the hybrid gates;
  rule 6 → gates + snapshots post-lock; the ladder gains senior/reader),
  [[TAG-BASED-AUTHORIZATION]] (assign-tags as its own decision; the define-tags deferral row),
  [[PARTIAL-EVALUATION-FILTERING]] (`filter` = `"list" ∈` effective, the inline-fold rationale,
  `<type>:list` in the recipe), [[HIERARCHICAL-AUTHORIZATION]] (the rego snippets speak
  `effective_actions`), `docs/guides/E2E-TESTING.md` + `infra/README.md` (the new matrix sections
  + the category-token payload note).
- [[USER-STORIES]]: **G1, G2, G3 ✅**; **G4 ✅ with the define-tags-deferral note inline**; the
  Epic G banner → shipped. [[POC-ROADMAP]]: the 6.5 row → ✅ shipped; the "Next" pointer → Phase 6.
- The [[PERMISSION-CATEGORIES]] index: banner → SHIPPED, status table ticked through T8,
  frontmatter → `status/done`.
- Mulch: the durable insights were recorded per-ticket as they emerged (the PE-inline idiom; the
  jsonb-`?`-JDBC gotcha; the uniform-422 spec decision; the hybrid-gate pattern + its test
  gotchas; the delta-dispatch pattern + its three knock-ons; the per-type-denial-vs-inheritance
  failure record) — nothing left unrecorded at close.
- `git mv docs/to-do/planning/PERMISSION-CATEGORIES docs/to-do/implemented/PERMISSION-CATEGORIES`.

## Tests

D1 — the guide exists; the six reconciled docs name the new vocabulary (grep-verified).
D2 — stories/roadmap/index consistent with the shipped state.
D3 — frontmatter valid on every touched note; wikilinks resolve; the clean-room scan clean
(fail-closed: `scripts/planning/cleanroom-patterns.local` present); the folder moved.

## Architecture review + refactor

Docs-only ticket; the review checked: no committed text references proprietary names (scan), the
guide states the fail-closed invariants exactly as implemented (∅-expansion, level-reject,
OPA-reject, no-fallback filter, denial-narrowing), and the T6/T7 contract notes (missing-id 404,
the E1 fixture semantics) are carried into the guide rather than left in STATUS notes. Nothing
substantive beyond that.

## Decisions

- Guide named `PERMISSION-MODEL.md` to avoid the wikilink clash with the [[PERMISSION-CATEGORIES]]
  index (as decomposed).

## Commit

`docs(permission-categories): PERMISSION-MODEL guide + reconciliations + slice record close (Phase 6.5 T8)`
