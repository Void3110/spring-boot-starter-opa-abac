---
tags:
  - status/active
  - type/review
  - area/api
  - area/architecture
---

# REST API design — review

> **Verdict**: **Sound and consistent; no fail-open found. Adopt a small set of conventions before the
> library is published.**
> **Scope**: the current public REST surface of both example services
> (catalog-management-service + [[USER-MANAGEMENT-SERVICE|user-management-service]]) assessed against
> [[REST-API-DESIGN]]. Static review of the OpenAPI specs, controllers, exception handlers, and
> `SecurityConfig`s. **No code was changed** — this is a findings + recommendations note.

This is a **design** review (shape of the API), not a slice branch review. It complements the per-slice
`/deep-review` notes; it asks "does the API, taken as a whole, hold together and match the bar?"

---

## Summary

The API is **internally consistent** across two independently-built services: same `/api/v1` versioning,
same plural-noun + ownership-nesting URL style, same `ApiError` envelope, same status-code vocabulary, the
same three authorization mechanisms applied the same way. That consistency is the headline strength — a
reviewer learns one set of rules and both services obey them.

The **fail-closed invariant holds** at the API layer: list endpoints cut rows in SQL (empty, not full
table, on no-grant); the catalog write path returns `503` rather than store untagged on a dictionary
outage; OPA errors deny. I found **no path that returns wider access on error or missing input** — the one
bug class that would matter most.

The findings below are **maturity gaps and one consistency wrinkle**, not correctness bugs. They are the
distance between "a clean demo" and "a published library's reference services". None block the current
slices; all are worth a deliberate pass before/around publication.

---

## Findings

| # | Severity | Finding | Recommendation |
|---|---|---|---|
| 1 | **Medium** | Error envelope is `{status, message, timestamp}` with no machine-stable `errorCode` and `application/json` (not `problem+json`). | Adopt RFC-7807 `problem+json` + an `errorCode` enum for a *published* library. (Guide §9 target.) |
| 2 | **Low** | No `Location` header on any `201`. | Emit `Location` from each create — cheap, the id is already known. |
| 3 | **Low–Medium** | Authorization asymmetry: `getCategory` uses load-then-check; `getCatalog`/`getProduct` use annotation-only. | Intentional (tags live only on categories) — **document it**, which the guide now does. Revisit when another resource gains tags. |
| 4 | **Low–Medium** | User-service public mutations are **authenticated but not ABAC-gated** (`UserController`, `POST /teams`). | Confirm this is the deliberate "anyone bootstraps themselves" demo seam, and **say so** at the endpoints; don't let it read as a forgotten `@OpaPreAuthorize`. |
| 5 | **Low** | No pagination — every list is an unbounded bare array. | Documented demo limitation; adopt a shared envelope once, library-wide, not per-endpoint. (Guide §7/§9.) |
| 6 | **Low** | No ABAC `actions`/`pageActions` metadata in responses. | That's **Phase 6 (action-enrichment)** on the roadmap — flagged, not a gap to fix now. |
| 7 | **Info** | `400/422/409/503` usage is correct but the *rule* lived only in the handlers. | Now codified in [[REST-API-DESIGN]] §3 so future endpoints decide it the same way. |

---

## Detail

### 1 — Error envelope: fine for a demo, thin for a published library *(Medium)*

Both `ApiExceptionHandler`s build the same `ApiError{status, message, timestamp}` with content type
`application/json`. The mapping is clean and the statuses are right. What's missing for a *library*
audience:

- **No machine-stable `errorCode`.** A consumer must branch on the human `message` string (which is also
  the thing most likely to change / be localized). A stable code (`TAG_KEY_UNKNOWN`, `ROLE_SUBSET_VIOLATION`)
  is what lets a client handle a `422` programmatically.
- **Not `application/problem+json`** (RFC-7807). The de-facto standard for HTTP error bodies; tooling and
  clients understand it. The current shape is a near-subset of it, so the migration is additive.
- **No `request`/instance path field** to correlate the error with the call that produced it.

This is the **highest-value** of the targets because the published artifact's whole point is to be a *good
example*. → Guide §9, first row. Not urgent for the slices in flight; worth a small dedicated pass.

### 2 — `Location` on `201` *(Low)*

No create emits a `Location` header — `createCatalog`, `createCategory`, `createProduct`, `POST /users`,
`POST /teams`, `addMember`, role/tag-definition creates all return `201` + body, no header. The body
already contains the server-assigned `id`, so the client isn't blocked, but `Location` is the standard
affordance and is essentially free to add (the controller already has the new id and the path template).
Low effort, low risk, nice polish.

### 3 — The load-then-check asymmetry *(Low–Medium, by design)*

A real, observable inconsistency:

| Read endpoint | Mechanism |
|---|---|
| `getCategory` | **load-then-check** — `categoryAuthorizer.require("read", entity)` (the row's **tags** drive the decision) |
| `getCatalog`, `getProduct` | **annotation-only** — `@OpaPreAuthorize(action="...:read", resourceType, resourceId="#id")` (subject + identity, no row tags) |

This is **correct for the demo**: only `CategoryEntity` carries dictionary tags, so only categories need
the per-instance tag decision; catalogs/products have nothing tag-shaped to load-and-check. But a reader
scanning the three controllers sees one read done differently and can't tell if it's intentional. **The
fix is documentation, not code** — and the guide ([[REST-API-DESIGN]] §5b) now states the rule: *reach for
load-then-check exactly when the stored resource's attributes decide.* When a second resource type gains
tags, it should follow categories. No fail-open here — annotation-only is the *stricter* path (it can't be
fooled by row tags it never loads).

### 4 — "Public" user-service mutations are authenticated, not authorized *(Low–Medium — confirm intent)*

Precise picture from `SecurityConfig`: in **both** services `/api/v1/**` is `.authenticated()` and
`/internal/**` is `permitAll()`. So nothing public is open to the *unauthenticated* world. But several
user-service mutations carry **no `@OpaPreAuthorize`**, meaning **any authenticated subject** can call
them:

- the whole `UserController` (`POST /users`, `GET /users`, `GET /users/{id}`),
- `POST /api/v1/teams` (create team) and the team reads.

By contrast `MembershipController`, `RoleDefinitionController`, `TagDefinitionController`, and team
`transfer-ownership` **are** `@OpaPreAuthorize`-gated on `#teamId`.

This is almost certainly the **intended bootstrap seam** — a new user must be able to create their own
user record and first team *before* they have any team membership to authorize against (a chicken-and-egg:
you can't be `team:manage`-authorized on a team that doesn't exist yet). That's a legitimate design. The
risk is **legibility**: an ungated mutation looks identical to a *forgotten* `@OpaPreAuthorize`. 

**Recommendation:** at each intentionally-ungated mutation, a one-line comment ("bootstrap: pre-membership,
authenticated-only by design") so the absence reads as a decision. The guide's §5 + §8 now frame
authenticated-vs-ABAC-gated explicitly; mirror that at the call sites. *(No change to behavior recommended
— just make the intent un-mistakable. If any of these were meant to be gated, that would upgrade to a real
finding; my read is they're deliberate.)*

### 5 — No pagination *(Low — documented limitation)*

`listCatalogs`, `listCategories`, `listProducts`, `GET /users`, `GET /teams`, members, role-defs,
tag-defs — all return bare unbounded arrays. Acceptable for the demo's volumes and it keeps the example
readable. It is **not** a pattern to copy into a production service, and the guide says so (§7). The right
move when it's time: a single shared `{count, page, perPage, items}` envelope adopted library-wide and
composed with the partial-eval filter — a slice, not a per-endpoint patch. Until then, **resist** adding
`limit`/`offset` to one list in isolation (it would fork the convention).

### 6 — No `actions` metadata *(Low — already roadmapped)*

No response carries `actions`/`pageActions` ("what may I do with this?"). This is exactly **Phase 6 —
action enrichment** ([[POC-ROADMAP]]); listed as a guide target only to point at it. Nothing to do here
beyond keeping the DTO shape ready for it.

### 7 — Status-code discipline is good; now it's written down *(Info)*

`400` (Bean-Validation/illegal-arg) vs `422` (domain-rule: tag/subset) vs `409` (state: conflict/immutable)
vs `503` (fail-closed dependency outage) is applied **correctly and consistently** across both handlers.
The only gap was that the *decision rule* lived implicitly in the handler code; it is now explicit in
[[REST-API-DESIGN]] §3 so the next endpoint author resolves the boundary the same way instead of guessing.

---

## Fail-closed verification

The load-bearing check for an authorization library — does any endpoint widen access on error/missing input?

| Path | Behavior on the bad case | Verdict |
|---|---|---|
| List endpoints (catalog/category/product, members, defs) | no grant → **empty list** (residual `DENY_ALL` ∧ scope), never full table | ✅ closed |
| `@OpaPreAuthorize` gate | OPA error / unauth / unresolved subject → **deny (403)** | ✅ closed |
| Load-then-check (`getCategory`) | missing row → `404` *before* authz; tag decision denies on doubt | ✅ closed |
| Catalog write + tag dictionary unreachable | **`503`**, resource **not stored** (no untagged write) | ✅ closed |
| Child addressed via wrong parent | `findByIdAndCatalogId` → **`404`**, no cross-parent leak | ✅ closed |
| `/internal/**` | `permitAll` **but** network-isolated, never gateway-fronted | ✅ closed *by deployment* (see note) |

**One thing to keep honest:** finding 4's ungated public mutations and the `/internal` `permitAll` both rest
on assumptions *outside* the code — that `/internal` is never routed by the gateway, and that "any
authenticated user may bootstrap" is acceptable. Those are correct for this demo, but they're **deployment
invariants**, not code invariants. The guide (§8) states them; a real deployment must enforce the network
isolation, because the code alone does not.

---

## What's done right

- **Two services, one set of conventions** — the strongest signal; consistency was clearly a goal, not an
  accident.
- **Spec-first** with `implements <Api>` — the contract is the reviewable artifact and drift is a build break.
- **Authorization is a first-class part of each endpoint** — three mechanisms (type-level, load-then-check,
  partial-eval list filter), each used where it fits, each fail-closed.
- **Status-code semantics are precise** — especially the `422`-for-domain-rules and `503`-for-fail-closed
  distinctions that many APIs get muddy.
- **Clean public/internal split** — `/api/v1` authenticated + ABAC; `/internal` network-isolated; not blurred.
- **No fail-open found.**

---

## Recommended follow-ups (priority order)

1. **(Medium)** RFC-7807 `problem+json` + stable `errorCode` — the publication-readiness item. A small slice.
2. **(Low)** `Location` header on every `201` — quick win.
3. **(Low)** One-line intent comments at the deliberately-ungated user-service mutations (finding 4).
4. **(Deferred)** Pagination envelope — a library-wide slice when data volume justifies it.
5. **(Roadmapped)** `actions`/`pageActions` — Phase 6, no action now.

None require touching the current slice work. Items 2 and 3 are the cheapest and could ride along with any
near-term API change.

---

## References

- [[REST-API-DESIGN]] — the guide this reviews against.
- [[ABAC-AUTHORIZATION]] · [[PARTIAL-EVALUATION-FILTERING]] · [[TAG-BASED-AUTHORIZATION]] — the auth
  mechanisms the endpoints use.
- [[TWO-LAYER-AUTHORIZATION]] — why `/internal` isolation is a deployment invariant.
- [[POC-ROADMAP]] — where action-enrichment (finding 6) lives.
