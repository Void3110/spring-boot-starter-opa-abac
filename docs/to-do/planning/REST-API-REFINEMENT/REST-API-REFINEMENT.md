---
tags:
  - status/planning
  - type/index
  - area/api
  - area/architecture
  - area/spring
---

# REST API refinement — the error contract (Phase 5.9)

> 🟡 **PLANNING** — branch `feature/void3110/rest-api-refinement` (to create). [[POC-ROADMAP]] **Phase
> 5.9**, pinned by ADR [[0011-error-contract-problem-json|0011]]. The first **publication-readiness** slice:
> it closes the highest-value maturity gap the [[REST-API-DESIGN-REVIEW|REST API design review]] found —
> **no fail-open exists**; these are the distance between "a clean demo" and "a published library's
> reference services".
>
> The review confirmed the API is sound, consistent, and fail-closed. This slice adopts the conventions
> the review flagged as **targets**: an RFC-7807 `application/problem+json` error body with a machine-stable,
> typed `errorCode`, plus two cheap ride-alongs (`Location` on `201`; intent comments at the deliberately-
> ungated bootstrap mutations).

This package mirrors the seven shipped slices ([[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]],
[[USER-MANAGEMENT-SERVICE]], [[TAG-DICTIONARY]], [[DATA-FILTERING]], [[HIERARCHY-SINGLE-RESOURCE]],
[[HIERARCHY-LIST-FILTER]]) 1:1 in structure. The design half (this index + [[00-DESIGN]]) is written from a
settled **ADR 0011**; the decomposition half (`01-DECOMPOSITION` + `10-QA-TEST-CASES` + the autonomous
prompt + STATUS stubs) is produced by the **slice-planner** skill.

## Why this slice

The [[REST-API-DESIGN-REVIEW]] assessed the whole public surface of both example services against
[[REST-API-DESIGN|the design guide]] and found **no fail-open** — only *maturity gaps*. The headline gap is
the **error envelope**: both services return `{status, message, timestamp}` at `application/json`, which is

- **not machine-actionable** — a consumer must branch on the human `message` string (the field most likely
  to change or be localized); there is no way to tell two `422`s apart (a bad tag value vs the role-subset
  rule), and
- **not `application/problem+json`** (RFC-7807) — the de-facto standard that tooling and clients understand.

For a published library whose *whole point is to be a good example*, the error contract is one of the most
visible surfaces. This slice makes it canonical.

> **Why before Phase 6 + 5.95.** Sequenced **first** so the action-enrichment `_actions` envelope (Phase 6)
> and the pagination envelope (Phase 5.95) both land on an already-RFC-7807-clean error surface — no second
> pass over the same handlers.

## The core idea (pinned by ADR 0011)

A **contract-shape** change, not a decision-logic one — there is **no authorization behavior change and no
fail-open to introduce**. Five pinned choices:

1. **Depth — the minimal additive RFC-7807 superset.** The body is the five standard members (`type`,
   `title`, `status`, `detail`, `instance`) + two documented extensions (`errorCode`, `timestamp`).
   **No hosted, dereferenceable type registry** — `type` is a stable, relative, opaque identifier (e.g.
   `/problems/tag-value-illegal`).
2. **Migration — clean replacement.** The legacy `ApiError {status, message, timestamp}` shape is
   **replaced** (`message` → `detail`; content type → `application/problem+json`), not kept alongside.
   Pre-publication, **no external consumers** — the only client is the repo's own e2e, which we control.
3. **Vocabulary — library-owned-and-extensible.** The library ships an **`ApiErrorCode` interface** + an
   **enum implementing it** for the failures *it* raises (the authorization-shaped ones); **each app
   extends** with its own enum implementing the same interface for its domain failures.
4. **Granularity — semantic.** One code per **distinct, client-actionable failure** the handlers already
   discriminate (`TAG_KEY_UNKNOWN` ≠ `ROLE_SUBSET_VIOLATION`, both `422`) — not one code per HTTP status.
5. **Typed in the contract.** `errorCode` is a **first-class typed enum member** of the `ProblemDetail`
   OpenAPI schema (not a free string or a loose `properties` bag) — so the generated client is typed and the
   vocabulary is self-documenting. This is why the library ships its own small `ProblemDetail` DTO rather
   than leaning on Spring's built-in (whose `properties` map would carry `errorCode` untyped).

## What this slice delivers

In the library:

- **`opa-abac-spring-security`** (where the advice-mappable exceptions live) —
  - an **`ApiErrorCode` interface** (`String code()`),
  - a **base enum** implementing it for the failures the library raises (e.g. `ACCESS_DENIED` 403,
    `DEPENDENCY_UNAVAILABLE` 503, `VALIDATION_FAILED` 400, `RESOURCE_NOT_FOUND` 404, `STATE_CONFLICT` 409,
    `TAG_VALUE_ILLEGAL` / `ROLE_SUBSET_VIOLATION` 422),
  - a small **`ProblemDetail`-shaped carrier** (the library's, so `errorCode` stays a typed contract member)
    and a reusable **advice base / mapping helper** the app advices build on. *(Exact placement — a shared
    advice base vs. a mapping utility — is a decomposition detail; the seam is "the library owns the carrier
    + its own codes + the mapping of library exceptions".)*

In each example service:

- a **service-specific `ApiErrorCode` enum** implementing the library interface, for that service's domain
  failures (catalog: e.g. immutable-/duplicate- conflicts; user-service: subset/team/role conflicts),
- the **`@RestControllerAdvice`** remapped to build the new `ProblemDetail` body (canonical members +
  `errorCode` + `timestamp`) at content type `application/problem+json`,
- the **OpenAPI spec** updated: `ApiError` → a `ProblemDetail` schema with the typed `errorCode` enum (the
  union of codes that service can emit); codegen stays clean (drift = build break),
- a **`Location` header** emitted from every `201` (the id + path template are already in hand),
- **one-line intent comments** at each deliberately-ungated bootstrap mutation (`UserController`,
  `POST /teams`) — "bootstrap: pre-membership, authenticated-only by design" — so an absent
  `@OpaPreAuthorize` reads as a decision, not a forgetting.

Docs:

- **`docs/guides/REST-API-DESIGN.md`** — move the error-contract / `Location` items from §9 *Targets* to the
  adopted body of §3/§4 (the guide currently frames them as targets; this slice realizes them).

## What this slice does NOT do (held for later)

- **Pagination** — a list-*shape* change that composes with the partial-eval residual → its own slice
  **[[POC-ROADMAP|Phase 5.95]]** (sequenced after this, before Phase 6). (ADR 0011 §boundary.)
- **`actions`/`pageActions` affordance metadata** — **Phase 6** ([[ACTION-ENRICHMENT]]). (Review finding #6.)
- **A hosted problem-type registry** — `type` values are stable opaque identifiers; no live docs site.
  (ADR 0011 §1.)
- **Any authorization behavior change** — the load-then-check vs annotation-only asymmetry (review finding
  #3) is **documentation, already done in the guide**; the ungated bootstrap mutations (finding #4) stay
  ungated **by design** — this slice only adds the explanatory comment, not a gate.

## File glossary

| File | Role |
|------|------|
| `REST-API-REFINEMENT.md` | This index — what the slice delivers, the glossary, the ticket status table, conventions. |
| `00-DESIGN.md` | The design: the `ProblemDetail` body, the `ApiErrorCode` interface + base/app enums, the clean replacement, the `Location` + intent-comment items, the proof posture, considered-&-rejected. |
| `01-DECOMPOSITION.md` | The ordered tickets (Goal / Deliverables / Acceptance / What-NOT-to-touch) + the critical path. **The work list.** *(produced by slice-planner)* |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | The self-contained prompt. *(produced by slice-planner)* |
| `10-QA-TEST-CASES.md` | Concrete unit / integration / e2e cases. *(produced by slice-planner)* |
| `STATUS-0N.md` | One per ticket, filled at each checkpoint during the autonomous run. *(produced by slice-planner)* |

## Ticket status

> Provisional (≈5 tickets; firmed up by slice-planner in `01-DECOMPOSITION`).

| # | Ticket | Module | Status |
|---|--------|--------|--------|
| T1 | Library: `ApiErrorCode` interface + base enum + `ProblemDetail` carrier + advice base/mapping helper + unit tests | spring-security | ✅ |
| T2 | Catalog: own `ApiErrorCode` enum + advice remap + OpenAPI `ProblemDetail` schema + `Location` on `201` + MockMvc IT | example-catalog | ✅ |
| T3 | User-service: own `ApiErrorCode` enum + advice remap + OpenAPI `ProblemDetail` schema + `Location` on `201` + intent comments at ungated bootstrap mutations + MockMvc IT | example-user | ✅ |
| T4 | e2e: extend existing matrices to assert `problem+json` + `errorCode` on existing negatives + `Location` on existing `201`s (no new collection) | e2e | ☐ |
| T5 | Docs (guide §3/§4 adopted from §9) + roadmap + Mulch + move folder to `implemented/` on ship | docs | ☐ |

**Critical path:** T1 → (T2 ∥ T3) → T4 → T5. T2 and T3 are **independent** once T1's library carrier +
interface land (each service adopts in isolation). T1 is independently landable (pure library + unit tests,
no app).

## Conventions (same as every prior slice)

- **Clean-room IP boundary.** Original neutral names only; the prior platform is **study-only**. Never copy
  proprietary source/names/paths. `errorCode` values are this repo's own neutral vocabulary.
- **`opa-abac-core` stays Spring-free** — the error-contract work lives in `opa-abac-spring-security` +
  the example services; **core is not touched**.
- **No fail-open / no authorization change** — this is a contract-shape change. The fail-closed posture the
  review verified (lists cut to empty, OPA error → deny, `503`-not-untagged-write) is **unchanged**; every
  error path still lands on the same status, now carrying a typed `errorCode`.
- **Spec-first** — the `ProblemDetail` schema change lands in the OpenAPI spec; codegen drift is a **build
  break** (the contract is the reviewable artifact).
- **Clean replacement, not a hybrid** — no legacy `message` field alongside `detail`; the body is canonical
  RFC-7807 + the two named extensions.
- **Commit identity** `Void3110 <void31102025@gmail.com>`; **one focused commit per ticket**; **do not
  push**. Mulch sync commits touch `.mulch/` only.

## Related

- ADR [[0011-error-contract-problem-json|0011]] — the error-contract decision this slice implements
  (problem+json depth, clean replacement, the `ApiErrorCode` vocabulary, semantic granularity, typed-in-spec).
- [[REST-API-DESIGN-REVIEW]] — the review this slice acts on (findings #1, #2, #4).
- [[REST-API-DESIGN]] — the guide (§3 status codes, §9 targets) this slice advances from "target" to
  "adopted".
- ADR [[0006-three-layer-enforcement-model|0006]] — the app layer whose error responses this shapes.
- [[POC-ROADMAP]] — Phase 5.9 (this slice); Phase 5.95 (pagination — next); Phase 6 ([[ACTION-ENRICHMENT]]).
- [[USER-STORIES]] — Epic F (adoption / publish), story **F3** (a published, machine-actionable error
  contract).
