---
tags:
  - status/active
  - type/decision
  - area/api
  - area/architecture
  - area/spring
---

# ADR 0011 — Error contract: RFC-7807 `problem+json` + a stable `errorCode` vocabulary

**Status:** Accepted (implemented — Phase 5.9, [[REST-API-REFINEMENT]])
**Date:** 2026-06
**Context tags:** REST API, error contract, RFC-7807, problem+json, errorCode, publication-readiness, clean-room

> This ADR pins the **error-contract fork** for **Phase 5.9 (REST API refinement)**. It governs the wire
> shape of every error body both example services return and the **machine-stable error vocabulary** a
> consumer branches on. It is the structural decision behind [[REST-API-DESIGN-REVIEW]] finding #1
> (Medium) — the one publication-readiness item with a "considered & rejected" list worth keeping. The
> two cheap ride-along items in the same slice (a `Location` header on `201`, intent comments at the
> deliberately-ungated bootstrap mutations) are **not** structural and need no ADR; they are tickets in
> the slice, not decisions in this record.

## Context

The [[REST-API-DESIGN-REVIEW|REST API design review]] assessed the public surface of both example
services against [[REST-API-DESIGN|the design guide]] and found it **sound, consistent, and with no
fail-open** — the findings are *maturity gaps*, not correctness bugs: the distance between "a clean demo"
and "a published library's reference services". The headline gap is the **error envelope**.

Today both services return the same body via a per-service `@RestControllerAdvice`, declared as `ApiError`
in **both** OpenAPI specs (so it is codegen'd, and drift is a build break):

```json
{ "status": 422, "message": "Unknown tag key: reglon", "timestamp": "2026-06-09T10:15:30Z" }
```

For a *published library whose whole point is to be a good example*, this is thin in two ways the review
names precisely:

1. **No machine-stable `errorCode`.** A consumer must branch on the human `message` string — which is also
   the field most likely to change or be localized. There is no way to handle a `422` *programmatically*
   (is it a bad tag value, or the role-subset rule? both are `422`).
2. **Not `application/problem+json`** (RFC-7807) — the de-facto standard for HTTP error bodies that tooling
   and clients understand. The current shape is a near-subset, so the migration is *additive in spirit*.

This is a **contract** decision, not a decision-logic one: there is **no fail-open to defend** (the review
confirmed none exists) and **no authorization behavior changes**. What is expensive to reverse — and
surprising to a future reader if undocumented — is (a) the *shape* of every error body across two
independently-built services and their generated specs, and (b) whether the error **vocabulary** is owned
by the library, by each app, or not typed at all. Hence this record.

The scope was settled in a planning interview (2026-06-09). The forks this ADR closes were the ones that
would otherwise stall an autonomous run mid-ticket on an unpinned contract detail.

## Decision

Five pinned choices.

### 1. Depth: the **minimal additive RFC-7807 superset**, not a hosted type registry

The error body becomes a canonical [RFC-7807](https://www.rfc-editor.org/rfc/rfc7807)
`application/problem+json` object — the five standard members plus **two documented extension members**:

| Member | Source | Notes |
|--------|--------|-------|
| `type` | new | a stable, **relative** URI reference (e.g. `/problems/tag-value-illegal`) identifying the problem kind. **No dereferenceable hosted registry** — the value is a stable identifier, not a live docs URL. |
| `title` | new | a short, human, **status-stable** summary of the problem kind (not the instance). |
| `status` | kept | the HTTP status, as today. |
| `detail` | renamed from `message` | the human, instance-specific explanation (RFC-7807's own field name). |
| `instance` | new | the request path that produced the error (correlation). |
| `errorCode` | new **extension** | the machine-stable code — see choice 3. |
| `timestamp` | kept as **extension** | retained for correlation; RFC-7807 explicitly permits extension members. |

A **hosted, dereferenceable problem-type registry** (each `type` URI resolving to a published docs page) is
**deliberately out of scope** — that is publication *infrastructure*, not a code slice, and adds a hosting
dependency a demo does not need. `type` is a stable opaque identifier here.

### 2. Migration: **clean replacement** of `ApiError`, not a back-compat superset

The legacy `{status, message, timestamp}` shape is **replaced**, not preserved alongside the new one:
`ApiError` → a `ProblemDetail`-shaped schema in **both** OpenAPI specs; `message` → `detail`; content type
`application/json` → `application/problem+json`. The pre-publication artifact has **no external consumers**
— the only client is the repo's own newman e2e, which we control — so a "breaking" change costs only
updating our own assertions (a *strengthening*: the negatives now pin the typed `errorCode`). A
back-compat superset would ship a *muddy* contract (a near-7807 body carrying both a legacy `message` and a
`detail`) — exactly the kind of wrinkle this slice exists to remove. A published **reference** service must
model the **canonical** shape, not a transitional hybrid.

### 3. The vocabulary is **library-owned-and-extensible**: an `ApiErrorCode` interface + a base enum

The machine-stable code is typed, *and* open to app extension:

- The library (`opa-abac-spring-security`, where the advice-mappable exceptions live) ships an **interface**
  `ApiErrorCode` (with a `String code()`) and an **enum implementing it** for the failures **the library
  itself raises** — the authorization-shaped ones: e.g. `ACCESS_DENIED` (403), `DEPENDENCY_UNAVAILABLE`
  (503, the fail-closed dictionary outage), `VALIDATION_FAILED` (400), `RESOURCE_NOT_FOUND` (404),
  `STATE_CONFLICT` (409), and the domain-rule rejections the library owns (`TAG_VALUE_ILLEGAL`,
  `ROLE_SUBSET_VIOLATION`) at 422.
- **Each example app extends** with its **own enum implementing the same `ApiErrorCode` interface**, for
  *its* domain failures. (A Java `enum` cannot be subclassed; the shared *contract* is the interface, so the
  slot stays typed while the set of codes is open.)
- The `@RestControllerAdvice` maps each handled exception → an `ApiErrorCode`; the serialized body carries
  `errorCode = code.code()`.

This mirrors how the library already splits responsibility — it owns the `@OpaPreAuthorize` / residual /
tag-validation mechanics; the app owns its domain rules — and it is the **example-worthy** shape: a consumer
sees the library hand it stable codes for the failures it owns *and* the recipe to add its own.

### 4. Granularity is **semantic**: one code per distinct, client-actionable failure

Codes distinguish *failures*, not *statuses*. `TAG_KEY_UNKNOWN` ≠ `ROLE_SUBSET_VIOLATION` even though both
are `422`; `STATE_CONFLICT` for an immutable-role edit ≠ a duplicate-team-target conflict if a client would
handle them differently. A coarse "one code per HTTP status" set would add **nothing** over the `status`
already in the body — the entire value of `errorCode` is discriminating *within* a status. The rule: a code
per distinct failure the handlers already discriminate and a client could plausibly branch on.

### 5. The `errorCode` enum is a **first-class typed member of the OpenAPI schema**

Because `errorCode` is what consumers branch on, it appears in the spec as a **typed enum** on the
`ProblemDetail` schema (the union of the codes a given service can emit), not as a free-form string or a
loose `properties` bag. This keeps the generated client typed and makes the vocabulary self-documenting in
the contract — which is the reason to prefer a **library-shipped `ProblemDetail` DTO** over leaning on
Spring Framework's built-in `org.springframework.http.ProblemDetail` (whose `properties` map would carry
`errorCode` untyped and would couple the *contract* shape to a Spring type).

## Considered options

| Option | Why not |
|--------|---------|
| **Full RFC-7807 with a hosted, dereferenceable type registry** (each `type` URI → a published docs page) | That is publication *infrastructure* (hosting + a maintained registry), not a code slice; it adds a runtime/hosting dependency a demo does not need. Stable opaque `type` identifiers give the contract value with none of the tail. |
| **Lean on Spring Framework's built-in `ProblemDetail` / `ErrorResponse`** as the carrier | Less custom code, but `errorCode` lands in the untyped `properties` map → it loses first-class typing in the OpenAPI schema (the one thing a consumer branches on), and it couples the *wire contract* to a Spring type. A small library-shipped DTO keeps `errorCode` a typed, codegen'd contract member. |
| **Back-compat additive superset** (keep `ApiError` + `message`, add the new fields as optional, stay `application/json`) | Produces a muddy near-7807 body with a legacy `message` *and* a `detail`. There are **no external consumers** (pre-publication), so the only cost of a clean break is our own e2e assertions — which we want to update anyway to pin `errorCode`. A reference service should model the canonical shape. |
| **No typed vocabulary — `errorCode` is a free string convention** | More "extensible", but re-introduces the exact problem the slice kills: a client branching on a stringly-typed value with no contract guarantee. The `ApiErrorCode` interface keeps it open *and* typed. |
| **Per-service enum only; the library ships no codes** | The library would then demonstrate the *envelope* but not a *shared code vocabulary* — and the authorization failures (deny, fail-closed 503, subset/tag rejections) are exactly the ones the library *owns* and should name. The interface + base-enum split lets the library name its own and apps name theirs. |
| **Coarse codes (one per HTTP status)** | Adds nothing over the `status` already in the body. The value of `errorCode` is discriminating *within* a status (which `422`?), so codes must be semantic. |
| **Drop `timestamp`** to stay strictly minimal | It is a cheap, genuinely useful correlation field and RFC-7807 permits extension members; keeping it (as an explicit extension) costs nothing and aids debugging. Kept. |
| **Fold pagination and/or action-enrichment into this slice** | Pagination is a list-*shape* change touching the partial-eval residual → its own slice **5.95** (sequenced before Phase 6). Affordance `_actions` metadata is **Phase 6**. This slice is the **error contract** only — a cohesive unit (one DTO + one interface/enum + two advices + two specs) that should not drift into two unrelated structural changes. |

## Consequences

- **Good:** every error body is canonical `application/problem+json`; consumers branch on a **typed,
  stable `errorCode`** that is first-class in the OpenAPI contract; the library **owns and names** the
  authorization failures it raises while apps extend for their domain; both services stay **consistent**
  (the review's headline strength preserved); the published artifact models the canonical error shape, not
  a transitional hybrid. **No authorization behavior changes; no fail-open introduced** (a contract-shape
  change only).
- **Cost:** a breaking change to the (consumer-less) error wire shape — both OpenAPI specs, both
  `@RestControllerAdvice`s, the library's new `ProblemDetail` DTO + `ApiErrorCode` interface + base enum,
  each app's own enum, and the e2e negative-case assertions all move together. Accepted because there are
  no external consumers yet and the result is the canonical contract the library should ship.
- **Boundary:** **no hosted problem-type registry** (stable opaque `type` identifiers only); **pagination**
  → slice 5.95; **`_actions` affordance metadata** → Phase 6. The intent-comment and `Location`-header
  items ride in the same slice but are not decisions in this record.
- **Proof posture:** a contract-shape change with no decision logic is proven by **unit (advice
  exception→code mapping; body serialization; `problem+json` content type) + OpenAPI codegen (drift = build
  break) + a lean per-service MockMvc integration test** (a representative error per status returns a
  well-formed `problem+json` with the right `errorCode`; a `201` carries `Location`), with **e2e extending
  the existing matrices** (assert the new body shape + `errorCode` on existing negative cases, `Location`
  on existing `201`s) rather than a new collection — appropriate where there is no fail-open and no gateway-
  specific behavior to exercise.

## Related

- [[REST-API-DESIGN-REVIEW]] (finding #1 — the review this pins; findings #2/#4 ride the same slice) ·
  [[REST-API-DESIGN]] (the guide — §3 status codes, §9 targets — this advances from "target" to "adopted").
- ADR [[0006-three-layer-enforcement-model|0006]] (the app layer whose error responses this shapes) ·
  ADR [[0005-partial-eval-to-jpa-specification|0005]] (the list path **pagination** (slice 5.95) will
  compose with — out of scope here).
- [[POC-ROADMAP]] (Phase 5.9 — this slice; Phase 5.95 — pagination; Phase 6 — action enrichment) ·
  [[USER-STORIES]] (the publication-readiness lens).
