---
tags:
  - status/planned
  - type/index
  - area/spring-data
  - area/abac
  - area/opa
---

# Data filtering — partial evaluation + batch evaluation

> **Status: Planning.** This folder is the full work package for [[POC-ROADMAP]] **Phase 5 — the
> advanced library slice**: the two mechanisms that make this starter stand out against a naive
> "call OPA per request" integration — **OPA partial evaluation → JPA `Specification`** row-level
> filtering, and **batch evaluation** for the residual per-item allowlist. It is written to be
> **implemented autonomously**: the design, the work breakdown, a self-contained
> [[AUTONOMOUS-IMPLEMENTATION-PROMPT]], the QA cases, and per-ticket STATUS stubs are all here.

This package mirrors the four shipped slices ([[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]],
[[USER-MANAGEMENT-SERVICE]], [[TAG-DICTIONARY]]) 1:1 in structure. On ship the folder moves to
`docs/to-do/implemented/DATA-FILTERING/` with a "Shipped" banner, keeping the prompt-and-results
record intact for the workflow-as-artifact comparison across runs.

## Why this slice

Everything shipped so far answers **one question at a time**: "may this subject do this action on
*this one* resource?" (`@OpaPreAuthorize`, role-definition-driven, tag-gated). That is the right
model for a single GET/POST. It is the **wrong** model for a **list** endpoint, where the honest
question is "of the N rows in the table, *which* may this subject see?" Answering that with N
single-decision calls (fetch-all-then-filter, or a call per row) is the naive integration — O(N)
round-trips and a full table scan before the filter.

This slice does it the way the source platform did and the way the one comparable (unmaintained)
OSS project gestures at: ask OPA to **partially evaluate** the policy for the current subject and
return the *residual conditions* — the unknowns that depend on each row — then compile those into a
JPA `Specification` and push them into the **SQL `WHERE` clause**. Authorization happens in the
database, over the existing `tags` JSONB column (already GIN-indexed for exactly this, since
[[DOMAIN-MODEL-FOUNDATION]]). For the residue that doesn't reduce to SQL, a **batch** decision call
collapses the per-row checks into one OPA round-trip.

> **The differentiator, stated plainly.** `@OpaPreAuthorize` is table stakes; many integrations
> have an equivalent. *Partial-eval → data filtering* is the piece almost nobody ships cleanly. This
> is the headline of Phase 5 and the strongest portfolio artifact in the repo. See
> [[RESEARCH-AUTOTAG-AND-FILTERING]] (§3) for the mechanism we generalized.

## What this slice delivers

In the library:

- **`opa-abac-core`** — a new **partial-evaluation client method** on `OpaClient`
  (`compile(AbacContext) → PartialResult`) backed by OPA's **Compile API** (`POST /v1/compile`),
  plus a neutral **residual-condition model** (`PartialResult` = `Decision{ALLOW_ALL, DENY_ALL,
  CONDITIONAL}` + a list of `Condition{path, operator, value}` in disjunctive normal form). Plus a
  **batch decision method** (`allowAll(List<AbacContext>) → List<Boolean>`) over OPA's bulk-input
  shape — designed as a **reusable primitive** (it is also the batch method Phase-6 action enrichment
  consumes; see [[ACTION-ENRICHMENT]]), not a filtering-only helper. Both **fail closed**. Core stays
  Spring-free.
- **`opa-abac-spring-data`** — a **`ResidualSpecificationFactory`** that translates a `PartialResult`
  into a Spring Data JPA `Specification<T>` over the `tags` JSONB column (operators `=`, `in`,
  `json-contains`/array-membership via `jsonb_extract_path_text` / `?`), with `ALLOW_ALL ⇒` no
  predicate (match all), `DENY_ALL ⇒` an always-false predicate (match none). Plus an optional
  **post-fetch allowlist filter** driven by the batch method for conditions that don't reduce to a
  predicate. A small **`AbacQueryService`** seam ties "build the context → compile → specification →
  query" together.
- **`opa-abac-spring-boot-starter`** — wire the new beans (`ResidualSpecificationFactory`,
  `AbacQueryService`), conditional + overridable, with a `partialEval.enabled` toggle.

In the example + infra:

- **catalog list endpoints** (`GET /catalogs`, `…/categories`, `…/products`) adopt the filtered path:
  repositories gain `JpaSpecificationExecutor`; the list handlers build the residual specification and
  return only the rows the subject may see.
- **`category.rego`** gains a partial-eval-friendly **filter entrypoint** (`data.category.filter`) whose
  body references `input.resource.tags[...]` as *unknowns* so the Compile API returns row-shaped
  residuals. The `filter` rule is **role-definition-only** (it does **not** inherit the shipped
  subject-roles fallback) so a missing role definition fails *closed* to an empty list, never an
  unfiltered table — see the fail-closed boundary in [[00-DESIGN]] and ADR
  [[0005-partial-eval-to-jpa-specification|0005]].
- an **e2e list-filtering matrix** proving the decisive contrast: two subjects hit the **same** list
  endpoint and get **different row sets** — and the filtering happens in SQL (asserted by the residual,
  not a post-filter), with a third "allow-all" subject seeing everything.

## What this slice does NOT do (held for later)

- **Hierarchical ancestor-walk** authorization (a Category inheriting its Catalog's grant) — the
  general per-instance path the tag demo stubbed with the app-layer `CategoryAuthorizer` load-then-check.
  Phase 5 filters by the resource's **own** attributes/tags; ancestor inheritance is a follow-up.
- **Action enrichment** (the `_actions` affordance map) — **Phase 6**, the first consumer of this slice's
  `allowAll` batch primitive ([[ACTION-ENRICHMENT]]).
- **Coarse permission categories + delegation** (`READ`/`WRITE`/`TAG`/`GRANT` expansion) — **Phase 6.5**,
  ADR [[0007-coarse-grained-permission-categories|0007]]. The `filter` rule here stays **flat-verb**
  (`category:read`) until then; 6.5 retrofits category expansion, additively.
- **ReBAC-in-Rego** (the team/membership/grant join in policy) — that's **Phase 8**.
- **`@AutoTag` auto-population** — orthogonal machinery, still deferred ([[RESEARCH-AUTOTAG-AND-FILTERING]] §1).
- Any change to the single-decision `@OpaPreAuthorize` path — it stays exactly as shipped.

## File glossary

| File | Role |
|------|------|
| `DATA-FILTERING.md` | This index — what the slice delivers, the glossary, the ticket status table, the critical path, conventions. |
| `00-DESIGN.md` | The design: the Compile-API call, the residual-condition model (DNF), the `Specification` translation over JSONB, the batch path, the rego filter entrypoint, fail-closed posture, and considered-&-rejected. |
| `01-DECOMPOSITION.md` | The ordered tickets (T1–T7), each with Goal / Deliverables / Acceptance / What-NOT-to-touch, + a cross-cutting acceptance block. **The work list.** |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | The self-contained prompt: branch, read order, per-ticket loop (prime → build → test → ★ architecture-review+refactor gate → integration/e2e → docs → Mulch → one commit → checkpoint), hard rules. |
| `10-QA-TEST-CASES.md` | Concrete unit / integration / e2e cases the implementation must satisfy. |
| `STATUS-01.md … STATUS-07.md` | One per ticket — filled in at each checkpoint during the autonomous run (what shipped · tests · architecture review + refactor · integration/e2e · decisions · commit). |

## Ticket status

| # | Ticket | Module | Status |
|---|--------|--------|--------|
| T1 | Partial-eval client: `OpaClient.compile` + `PartialResult`/`Condition` model + Compile-API call | core | ☐ |
| T2 | Batch decision: `OpaClient.allowAll` (bulk input) | core | ☐ |
| T3 | `ResidualSpecificationFactory` — residuals → JPA `Specification` over JSONB | spring-data | ☐ |
| T4 | `AbacQueryService` seam + optional post-fetch allowlist (batch) | spring-data | ☐ |
| T5 | Starter wiring (beans, `partialEval.enabled`, overridable) | starter | ☐ |
| T6 | Example adoption: `JpaSpecificationExecutor`, filtered list handlers, `category.rego` filter entrypoint | example + infra | ☐ |
| T7 | e2e list-filtering matrix + docs + roadmap/Mulch | e2e + docs | ☐ |

**Critical path:** T1 → T3 → T4 → T5 → T6 → T7. **T2 runs parallel with T1** (both pure-core, unit
tested with an in-process HTTP stub). T4's allowlist depends on T2.

## Conventions (same as every prior slice)

- **Clean-room IP boundary.** Original neutral names only; the source platform is **study-only**
  ([[POC-ROADMAP]] → "IP boundary", root `CLAUDE.md`). Never copy proprietary source/names/paths.
- **`opa-abac-core` stays Spring-free** — the Compile-API call uses the JDK `HttpClient` + Jackson,
  no Spring. The `Specification` translation lives in `opa-abac-spring-data`.
- **Fail-closed everywhere** — a compile/transport/parse failure yields `DENY_ALL` (empty result set),
  never a silent `ALLOW_ALL`. Widening on failure is the one thing this slice must never do.
- **Commit identity** `Void3110 <void31102025@gmail.com>`; **one focused commit per ticket**; **do not
  push** (the maintainer pushes). Mulch sync commits touch `.mulch/` only.

## Related

- **The pinned decisions:** ADR [[0005-partial-eval-to-jpa-specification|0005]] (partial-eval →
  `Specification`, the central fork this slice implements) · ADR
  [[0006-three-layer-enforcement-model|0006]] (the three-layer model this is **layer 3 — DB** of).
- [[POC-ROADMAP]] — Phase 5 (this slice), Phase 6 ([[ACTION-ENRICHMENT]]), Phase 6.5 (ADR 0007), Phase 8 (ReBAC-in-Rego).
- [[RESEARCH-AUTOTAG-AND-FILTERING]] — §3 scoped the partial-eval → `Specification` mechanism.
- [[DOMAIN-MODEL-FOUNDATION]] — the `tags` JSONB column + the GIN index this filters over.
- [[LIBRARY-SPINE]] — the `OpaClient` / `AbacContext` this extends.
- [[TAG-DICTIONARY]] — the `tags_satisfied` match the filter entrypoint generalizes to a list query.
