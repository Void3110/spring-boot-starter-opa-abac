---
tags:
  - status/done
  - type/index
  - area/api
  - area/spring
  - area/abac
---

# Pagination envelope (Phase 5.95)

> ✅ **SHIPPED** (2026-06-11, branch `feature/void3110/pagination-envelope`, T1–T6). [[POC-ROADMAP]]
> **Phase 5.95**, pinned by ADR [[0012-pagination-envelope|0012]]. The second **publication-readiness**
> slice after 5.9: every public list endpoint adopted one shared `{count, page, perPage, items}`
> envelope, composed with the Phase-5 partial-eval filter — the *filtered* row set paginates, and
> `count` is the subject-relative authorized total on **every** query path. A list-**shape** change
> only: **no authorization behavior changed, zero Rego changes, `opa-abac-core` untouched.**
>
> **What shipped:** the additive paged `findAuthorized(…, Pageable)` → `Page<T>` overload in
> `opa-abac-spring-data` (the unsorted-`Pageable` guard; exact counts on all four paths — the allowlist
> fallback pages its SQL-sorted in-memory result at unchanged Phase-5 cost); `PageEnvelope` +
> `<Resource>Page` `allOf` schemas and shared `page`/`perPage` parameters in both OpenAPI specs (strict
> 0-based contract: defaults 0/20, bounds 1–100, `400 VALIDATION_FAILED` on violation, past-the-end =
> `200` + empty + exact `count`); fixed `createdAt ASC, id ASC` ordering everywhere; all 9 public lists
> converted (only `listCategories` on the residual path, as before); `/internal/**` unpaginated by
> design; guides [[REST-API-DESIGN]] §7 + [[PARTIAL-EVALUATION-FILTERING]] (paged composition) +
> [[E2E-TESTING]] updated; `run-pagination-matrix.sh` green through the gateway (27/27; the whole suite
> 128 assertions) on the dedicated `7777…` fixture set. Proof and per-ticket detail: `STATUS-01…06`.

This package mirrors the eight shipped slices ([[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]],
[[USER-MANAGEMENT-SERVICE]], [[TAG-DICTIONARY]], [[DATA-FILTERING]], [[HIERARCHY-SINGLE-RESOURCE]],
[[HIERARCHY-LIST-FILTER]], [[REST-API-REFINEMENT]]) 1:1 in structure. The design half (this index +
[[00-DESIGN]]) is written from the settled **ADR 0012**; the decomposition half (`01-DECOMPOSITION` +
`10-QA-TEST-CASES` + the autonomous prompt + STATUS stubs) is produced by the **decompose** skill.

## Why this slice, and why now

- [[REST-API-DESIGN-REVIEW]] finding #5: every list is an unbounded bare array — acceptable for the demo,
  not a pattern a published library's reference services should model. The standing instruction: adopt a
  shared envelope **once, everywhere**, never ad-hoc `limit`/`offset` on one endpoint.
- **Sequenced after 5.9, before Phase 6** (decided 2026-06-09): pagination is a list-*shape* change and
  action enrichment *adds fields to list items*, so paginating first means the `_actions` envelope lands on
  the final list shape in one pass.
- The structural fork — how a page composes with the partial-eval residual, and what `count` means on the
  allowlist-fallback path — is pinned in ADR [[0012-pagination-envelope|0012]], with the full design in
  [[00-DESIGN]].

## Contents

| Doc | What it holds |
|---|---|
| [[00-DESIGN]] | the settled design: the four-path paged seam, the wire contract, ordering, fail-closed posture, proof posture |
| [[01-DECOMPOSITION]] | the 6 ordered tickets (Goal / Deliverables / Acceptance / What-NOT-to-touch) + cross-cutting acceptance + the critical path |
| [[10-QA-TEST-CASES]] | the U1–U8 / I1–I9 / C1–C2 / E1–E4 cases + the fail-closed checklist |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | the verbatim self-contained run prompt (flow guide §4 template, slots filled) |
| `STATUS-01…06` | per-ticket run logs, filled at each checkpoint |

## Ticket status

> **Critical path: T1 → (T2 ∥ T3 ∥ T4) → T5 → T6.** T1 + T2 are independently landable (library-only).
> Branch: `feature/void3110/pagination-envelope`.

- [x] **T1** — Library: paged `findAuthorized` overload (all four paths) + the unsorted-`Pageable` guard + unit tests
- [x] **T2** — Library IT (real Postgres): `PaginationListIT` — the two-subject `count` contrast + the `perPage=2` stability walk
- [x] **T3** — Catalog: spec envelope + paged controllers + `CategoryListAuthorizer` pass-through + IT
- [x] **T4** — User-service: spec envelope ×6 list ops + paged services/controllers + the `/internal` note + IT
- [x] **T5** — e2e: the pagination matrix + the fixture set + the suite-wide envelope migration
- [x] **T6** — Docs (§7 adopted) + PARTIAL-EVALUATION-FILTERING + roadmap + Mulch + folder move

## Conventions

- **Clean-room:** every committed word is public — original neutral names only (root `CLAUDE.md` → IP boundary).
- **Commit identity:** `Void3110 <void31102025@gmail.com>`, one focused commit per ticket; the maintainer pushes.

## Related

- ADR [[0012-pagination-envelope|0012]] · [[00-DESIGN]] — the decision + the design.
- [[POC-ROADMAP]] (Phase 5.95) · [[USER-STORIES]] (Epic D — D5).
- [[REST-API-DESIGN-REVIEW]] (finding #5) · [[REST-API-DESIGN]] (§7/§9).
