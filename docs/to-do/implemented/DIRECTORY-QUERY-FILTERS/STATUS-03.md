---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS — T3: `produces` spec fix — 204 endpoints accept a JSON Accept

**Status:** ✅ DONE

## What shipped

- `user-mgmt-api.yaml` — a shared **`components/responses/NoContent`** (design §2.4's preferred
  shape, one definition point, matching the existing `BadRequest`/`NotFound`/… idiom): a 204 with an
  **empty, schema-less `application/json` content declaration** (`application/json: {}`). All four
  204-only ops now `$ref` it: `transferOwnership`, `deleteRoleDefinition`,
  `deleteTeamTagDefinition`, `removeMember`.
- Regenerated interfaces: `produces` widens from `{application/problem+json}` to
  `{application/json, application/problem+json}` on those four ops; **return types stay
  `ResponseEntity<Void>`** — zero Java changes in this ticket, no controller edit, no build-breaker.

## Tests

- **I3 — `NoContentAcceptIT`** (Testcontainers, real chain, real gates, acting as the team owner):
  each of the four ops called via the JDK `HttpClient` (exact header control —
  `TestRestTemplate` always volunteers an `Accept`, which would un-test the case) with
  (a) a bare `Accept: application/json` → **204, empty body — not 406**, and
  (b) **no `Accept` header at all** → 204, empty body (the regression guard). Fresh fixtures per
  header variant (the ops are destructive): team + owner + removable member + successor member +
  a bootstrap-seeded custom role + an owner-created tag key.
- **Negative control (run, not just reasoned):** the same IT against the *pre-fix* spec —
  `bareJsonAccept…` **fails with `expected: 204 but was: 406`** and `absentAccept…` passes. The test
  asserts the actual cut; the no-Accept path was never broken.
- `./gradlew :example-user-management-service:test --rerun` — **189 tests, 0 failures** (whole module).

## Architecture review + refactor

- **Fail-closed / no-widening:** no decision logic touched — the change is the *declared producible
  type* of four success paths. The **204 status + absent body are byte-for-byte unchanged** (both I3
  variants assert an empty body); error responses stay `application/problem+json` only.
- **Security:** the ops stay behind their existing `@OpaPreAuthorize` gates (the IT exercises them
  through the real advisor as the owner). Nothing new is disclosed — no representation is ever
  produced; only negotiation admits a JSON `Accept`.
- **Concurrency / idempotency:** no mutation-path change.
- **Wiring:** the non-happy path *is* the pre-fix 406 — pinned by the executed negative control, not
  by assumption.
- **Boundary / additivity:** spec-only; one shared component (`NoContent`), four `$ref`s. The
  considered-and-rejected Java `produces` override (design §2.4) stays rejected — the OpenAPI stays
  the source of truth.
- **Refactor applied:** none — nothing substantive; the shared component *is* the reviewed shape.

## Integration / e2e

I3 ran in the module test task — green. Gateway e2e (E3, a `removeMember` 204 through APISIX with a
JSON `Accept`) is T5's newman extension.

## Decisions

- **Shared `NoContent` component over per-op declarations** — one definition point; the cost is the
  loss of the op-specific 204 descriptions ("Transferred"/"Deleted"/"Removed") in favor of one
  generic description. Cosmetic; accepted.
- **The media type is deliberately schema-less** (`application/json: {}`): a schema (even `{}`
  as an empty object schema) would flip the generated return type from `ResponseEntity<Void>` to
  `ResponseEntity<Object>` and break every controller override. Schema-less widens `produces` only.
  Recorded in Mulch (`api-design` pattern).

## Commit

`fix(usermgmt): admit a JSON Accept on the four 204-only ops (T3)` — see git log on
`feature/void3110/directory-query-filters`.
