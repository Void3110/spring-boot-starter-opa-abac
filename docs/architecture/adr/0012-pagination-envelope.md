---
tags:
  - status/active
  - type/decision
  - area/api
  - area/architecture
  - area/spring
---

# ADR 0012 — Pagination envelope composed with ABAC list filtering

**Status:** Accepted (planned — Phase 5.95, [[PAGINATION-ENVELOPE]])
**Date:** 2026-06
**Context tags:** REST API, pagination, list envelope, partial evaluation, `Pageable`, deterministic ordering, publication-readiness

> This ADR pins the **pagination fork** for **Phase 5.95**. It governs the wire shape of every public
> list response, the library seam that paginates an ABAC-filtered query, and the determinism guarantees
> that make paging over a *subject-relative* row set correct. It is the structural decision behind
> [[REST-API-DESIGN-REVIEW]] finding #5 and guide [[REST-API-DESIGN]] §7/§9. The scope was settled in a
> planning interview (2026-06-11); the forks closed here are the ones that would otherwise stall an
> autonomous run mid-ticket on an unpinned contract semantic.

## Context

Every public list endpoint in both example services returns a **bare, unbounded array** — documented as a
demo limitation (guide §7) and flagged by the design review (finding #5) with a standing instruction:
adopt a shared envelope **once, everywhere**, composed with the partial-eval filter; never bolt
`limit`/`offset` onto one endpoint in isolation.

What makes this more than API polish is the composition with **Phase 5 data filtering**
(ADR [[0005-partial-eval-to-jpa-specification|0005]], [[0010-hierarchy-aware-list-filter|0010]]):
`AbacQueryService.findAuthorized` answers a list query on one of **four paths** — the pure-SQL residual
path, the allowlist-batch fallback (a not-fully-SQL residual → fetch all scoped candidates, batch re-check
in memory), the `partialEval.enabled=false` kill-switch path, and the `fromError` fail-closed cut. A
pagination contract must hold on **all four**, and the "total count" under ABAC is **subject-relative**:
two users paging the same URL legitimately see different counts. That interaction — not the envelope JSON —
is the expensive-to-reverse part, hence this record.

## Decision

Five pinned choices.

### 1. The envelope carries an **exact `count` on every path** — no Slice shape, no sometimes-`null` hybrid

The wire envelope is `{count, page, perPage, items}` with `count` = the total number of rows **the calling
subject is authorized to see** across all pages (never `items.length`):

- **Pure-SQL path:** Spring Data's paged `findAll(spec, pageable)` issues the `COUNT` query over the same
  combined `Specification` — exact by construction, one cheap extra query.
- **Allowlist-fallback path:** the path already fetches **all** scoped candidates today (an accepted,
  kill-switchable Phase-5 degradation); the batch-filtered in-memory result is sliced to the requested page
  and its size **is** the exact `count`. Pagination adds **no new cost** to this path — that is the
  observation that makes exact-count affordable.
- **Kill-switch path:** the paged `scope.and(notDenied)` query counts the same way as any Spring Data page.
- **`fromError`:** an empty page with `count = 0` — the fail-closed cut, unchanged.

A `hasNext`-only Slice shape would avoid a cost we are already paying, reads worse in the demo (no page
numbers), and weakens the e2e contrast (`count` flipping between two subjects on the same URL is the
decisive filtered-list assertion). A hybrid (`count: null` when degraded) makes clients branch on a
sometimes-field — a contract smell.

### 2. The library seam is **Spring-native**: an additive `Pageable` overload returning `Page<T>`

`opa-abac-spring-data` gains `findAuthorized(repo, scope, queryContext, subtreeSpec, Pageable)` returning
`Page<T>`, alongside the existing `List<T>` overloads (the same additive back-compat move as 5.5-B's 4-arg
overload). The library speaks `Pageable`/`Page` — the idiomatic Spring contract, per the repo's
"natively Spring-friendly, no bespoke worldview" thesis. The **wire envelope is owned by the example
services' OpenAPI specs**, not the library: controllers map `Page<T>` → generated `<Resource>Page` DTOs.
`opa-abac-core` is untouched (no pagination types; Spring Data enters only where it already lives).

In the specs (vanilla codegen, no custom templates): one 3-field `PageEnvelope` component per spec file
(`count`, `page`, `perPage`; required; bounds expressed as schema constraints), each resource composed via
`allOf` into a `<Resource>Page` schema adding the typed `items` array; `page`/`perPage` as shared
`components/parameters`. The base is deliberately defined **twice** (once per spec) rather than cross-file
`$ref`-ing a shared YAML, which would couple the two services' build inputs.

### 3. The params contract is **strict and 0-based**

| Semantic | Pinned |
|---|---|
| Wire params | `page`, `perPage` |
| Page base | **0-based** — `page` maps to `PageRequest.of(page, perPage)` with no translation layer (and no off-by-one nest) |
| Defaults | `page=0`, `perPage=20` |
| Bounds | `page >= 0`, `1 <= perPage <= 100` |
| Violations | **`400 VALIDATION_FAILED`** (`problem+json`, ADR [[0011-error-contract-problem-json|0011]]) — **no clamping**: silently returning 100 rows when 500 were requested changes meaning without telling the client |
| Past-the-end page | **`200`, empty `items`, exact `count`** — never `404`. Under ABAC the "last page" is subject-relative; a 404 would confuse paging loops and read as an existence probe |
| Envelope echo | `page`/`perPage` echo the request verbatim (trivially true once nothing clamps) |

No new error codes are needed — `VALIDATION_FAILED(400)` exists.

### 4. Determinism **by construction**: a fixed total order, enforced at the seam

- **Fixed server-side order on all endpoints: `createdAt ASC, id ASC`.** `createdAt` alone is not a total
  order (same-transaction inserts share timestamps); the `id` tiebreaker makes it one. Every entity
  inherits both from `AbstractAuditableEntity`.
- **The paged `findAuthorized` overload rejects an unsorted `Pageable`** (`IllegalArgumentException`,
  fail-loud at dev time). Paginating without a total order is a *correctness* bug that surfaces as rows
  silently repeating or vanishing across pages — the seam refuses it. (An unpaged `Pageable` fails the
  same guard — it carries no sort.)
- **Order survives the fallback path:** the allowlist branch fetches its candidates **SQL-sorted**
  (`findAll(spec, sort)`); the batch filter preserves order; the in-memory slice pages the same sequence
  the pure-SQL path would. Without this the two paths would return differently-ordered pages — a
  path-dependent contract leak.
- **Client-specified `sort` is out of scope** — a deferred target (guide §9). It multiplies the OpenAPI
  surface and the sortable-field-allowlist validation story while proving nothing new about the residual
  composition.

### 5. Scope: **envelope everywhere, authorization semantics nowhere**

All **9 public list endpoints** adopt the envelope; **none** change their authorization shape. Only
`listCategories` flows through the paged `findAuthorized` (it is the Phase-5 residual demo); `listCatalogs`,
`listProducts`, and the six user-service lists keep their `@OpaPreAuthorize` gates over plain Spring Data
pagination. Converting catalogs/products to residual filtering would be a behavioral change (their row sets
could shrink) smuggled inside a shape slice — if ever wanted, that is its own decision.

- **`/internal/**` stays unpaginated, deliberately** — guide §8 pins that surface as plain, bounded,
  network-isolated shapes; a one-line "unpaginated by design" note keeps the absence legible.
- **The wire change is a clean break** (bare array → envelope): pre-publication, no external consumers,
  the repo's own newman collections update in the same slice. No dual-shape compatibility mode.
- **Zero Rego changes.** Pagination is not a policy concern: OPA still answers *which rows*; `LIMIT/OFFSET`
  decides *how many per page*. `opa test` stays untouched.

## Considered options

| Option | Why not |
|--------|---------|
| **Slice-shaped envelope** (`hasNext`, no `count`) | Avoids a count cost we already pay (the fallback path is fetch-all today); loses page numbers for the demo UI and the decisive two-subjects-different-`count` e2e contrast. |
| **Hybrid count** (`count: null` when the fallback engages) | A sometimes-field clients must branch on — a contract smell; contradicts "explainable and teachable". |
| **Library-owned envelope type** (`AbacPage<T>`) | Insulates against `Page`'s unstable JSON — but nothing serializes `Page` directly (controllers map to OpenAPI DTOs); a bespoke carrier is exactly the "mediator worldview" the thesis rejects. |
| **Serialize Spring's `Page`/`PagedModel` directly** | Couples the wire contract to Spring types and bypasses the codegen'd, spec-owned contract (the same reasoning that rejected Spring's `ProblemDetail` in ADR 0011). |
| **1-based `page`** | Human-friendlier, but buys a permanent `page - 1` translation at every controller — the classic off-by-one nest; 0-based is Spring's own `Pageable` semantic, taught explicitly instead. |
| **Clamp `perPage` to the max** (GitHub-style lenient) | Lenient-and-silent changes meaning without telling the client; strict `400` with a typed code matches the repo's 400/422 discipline and is crisply testable. |
| **`404` past the last page** | The last page is subject-relative under ABAC; 404 breaks idempotent paging loops and reads as an existence probe. |
| **Client `?sort=` now** | Multiplies the spec surface + a sortable-field allowlist contract; proves nothing new about residual composition. Deferred target (§9). |
| **Cursor/keyset pagination** | More robust under concurrent writes, but a heavier contract (opaque cursors) with no extra teaching value for the residual composition; offset paging over a fixed total order is the right demo register. |
| **Residual-filter `listCatalogs`/`listProducts` while we're in there** | An authorization-behavior change hidden in a shape slice — the exact class of unpinned drift that pauses autonomous runs. |
| **Custom codegen templates for a generic envelope** | Fights the repo's vanilla-`org.openapi.generator` convention for zero demo value; `allOf` composition gives one definition point per spec. |
| **Cross-file shared `$ref` for the envelope base** | Couples the two services' build inputs; two copies of three fields, guarded by the guide convention, is the lesser evil. |
| **Paginate `/internal/**` too** | Bounded machine-to-machine payloads on a network-isolated surface; pagination would add contract weight where §8 deliberately keeps plain shapes. |

## Consequences

- **Good:** every public list is bounded with one uniform, codegen'd envelope; `count` is the
  subject-relative authorized total on **every** path — the teachable headline ("the count is the count of
  rows *you* may see"); pagination is deterministic by construction (fixed total order + the unsorted-
  `Pageable` guard); the library seam stays idiomatic Spring; **no authorization behavior changes, no Rego
  changes, no fail-open introduced**.
- **Cost:** a breaking change to the (consumer-less) list wire shape — both OpenAPI specs, all 9
  controllers, the newman collections, and the paged `findAuthorized` overload move together. The
  allowlist-fallback path retains its Phase-5 fetch-all cost (now also the price of its exact `count`) —
  unchanged, documented, kill-switchable.
- **Boundary:** client `sort` → deferred target; cursor pagination → not planned; `_actions` affordances →
  Phase 6 (which lands on this envelope — the sequencing reason 5.95 precedes it); residual-filtering the
  coarse-gated lists → its own decision if ever.
- **Proof posture:** library unit tests over **all four paths** + the unsorted-`Pageable` guard; a
  real-Postgres IT walking all pages at `perPage=2` asserting the union is exactly the authorized set with
  no repeats/drops (the determinism regression test) and two-subjects-different-`count`; per-service MockMvc
  ITs for the envelope shape, strict-400 negatives, and past-the-end; a `run-pagination-matrix.sh` newman
  matrix through the gateway with a **dedicated, namespaced pagination fixture set** in the e2e registry
  (shared-seed exact-count assertions elsewhere must not be perturbed).

## Related

- [[REST-API-DESIGN-REVIEW]] (finding #5 — the review this pins) · [[REST-API-DESIGN]] (§7 — rewritten by
  the slice; §9 — the target this adopts).
- ADR [[0005-partial-eval-to-jpa-specification|0005]] + ADR [[0010-hierarchy-aware-list-filter|0010]] (the
  list paths this paginates) · ADR [[0011-error-contract-problem-json|0011]] (the error surface the
  strict-400 negatives land on) · ADR [[0006-three-layer-enforcement-model|0006]] (the DB layer the `COUNT`
  composes with).
- [[POC-ROADMAP]] (Phase 5.95 — this slice; Phase 6 — action enrichment lands on this envelope) ·
  [[USER-STORIES]] (Epic D — D5) · [[PAGINATION-ENVELOPE]] (the slice this ADR pins).
