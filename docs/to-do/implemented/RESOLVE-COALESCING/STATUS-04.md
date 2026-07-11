---
tags:
  - status/implemented
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T4: Batch wire — /internal/effective-roles + HttpRoleDefinitionSupplier.lookupAll

**Status:** ✅ DONE — the batch is real over HTTP: one guarded GET resolving N targets, B2's strict
classification batched, strict completeness enforced client-side, `/internal/**` unrouted.

## What shipped

- **`InternalResolveController.effectiveRoles`** (user-mgmt, `…usermgmt.web`):
  `GET /internal/effective-roles?userId=…&target=<type>:<id>&…` → `200` with a JSON array of
  `{resourceType, resourceId, role|null}` — **exactly one entry per requested target, never `204`**
  (no-role travels in-body; `@JsonInclude(ALWAYS)` pins the explicit `null` against any app-wide
  `NON_NULL` default). **Every target is validated before any is resolved** (a malformed request
  never gets a partial answer): `400` for a missing `userId`/`target`, a malformed target
  (no colon / empty part / non-UUID id), or a **duplicate target** (it would make
  one-entry-per-target ambiguous). Server failures stay `5xx` — never a fabricated partial body
  (5xx-over-partial, ADR 0024 §2). Resolution loops `EffectiveRoleService.resolveForResource`
  (in-process — the coalesced round-trips are the HTTP ones). Mounted under the existing permitted
  `/internal/**` block; **no APISIX route** (the rig additionally 404s `/internal/*` at the edge via
  the `internal-blocked` route).
- **`HttpRoleDefinitionSupplier.lookupAll`** (catalog, `…catalog.config`): empty set → `Map.of()`
  without HTTP; null `userId` → all-empty (the same no-coordinates posture as `lookup`); otherwise
  **one exchange through the existing `resolveCallGuard` as one guarded call** — the construction
  comment at the wrap point restates why the retry is safe (read-only GET, request thread, no
  transaction — ADR 0017 §3/§4). Classification mirrors `lookup` verbatim: 200+complete → the map
  (`role:null` → `Optional.empty()`); **missing/extra/duplicate entry → permanent whole-batch
  `RoleResolutionException`** (strict completeness); 200-blank/unparseable → permanent;
  5xx/429/timeout/connect → `TransientResolveException` → retried inside the guard → exhausted →
  `RoleResolutionException`; every 4xx → permanent, exactly one attempt; a `204` → permanent (not
  part of the batch contract); breaker-open → `RoleResolutionException` **without an exchange**.
  Target parts are form-encoded individually and joined with a literal `:` — a `:` inside a part
  arrives as `%3A`, so the `<type>:<id>` split is never ambiguous.

## Tests

- **U10** — `HttpRoleDefinitionSupplierBatchTest` (in-process `HttpServer`, no WireMock; the
  `EdgeResilienceTest` virtual-clock guard idiom): 14 tests covering the full classification table —
  one-exchange-per-batch (request-counted), explicit-null → empty, encoding, missing/extra/duplicate
  entry, blank/unparseable, transient-recovers (2 exchanges), exhausted 5xx (3 attempts), 429,
  4xx single-attempt, 204-outage, breaker-open-without-exchange, empty-set zero-HTTP, null-userId
  all-empty.
- **I1** — `EffectiveRoleBatchResolveIT` (Testcontainers Postgres, `TestRestTemplate`): mixed
  resolved/no-role targets → one entry each with the **explicit** `role:null`; unknown subject →
  null roles (authoritative, not an error); malformed targets / duplicate target / missing params →
  `400`.
- Full `./gradlew build` GREEN.

## Architecture review + refactor

Nothing substantive to refactor. Reviewed and held: validate-all-before-resolve-any on the server;
strict completeness enforced at the client (and again at the memo layer above it, T3); no PII in
batch WARN logs (status/class/counts only — mirrors `lookup`); the single-target `lookup()` and
`/internal/effective-role` (singular) are additive-untouched; the transient/permanent split reuses
`RetryableClassification` — not reinvented.

## Integration / e2e

The Testcontainers ITs above; the live in-network wire (through the real rig) is exercised by T6's
multi-root re-run (the advice starts calling `lookupAll` in T5).

## Decisions

- **Deviation from the decompose note, documented:** "unknown type → 400" became **structurally
  invalid → 400**. There is no server-side registry of team target types (teams govern arbitrary
  type strings — checked `TeamService`/`ExactTeamTargetMatcher`), so a well-formed unknown type is
  the honest authoritative `role:null` per entry; only a target the parser cannot split
  unambiguously is a 400. This is also the safer client posture: a 400 is a *permanent* outage —
  reserving it for structural defects keeps a mixed-type page resolvable.
- Duplicate targets are rejected server-side (400) rather than deduped — one-entry-per-target must
  stay unambiguous, and the library client never sends duplicates (it batches a `Set`).
- `amplification.py` needs no change for the new wire: `classify()` matches
  `/internal/effective-role` as a substring, so `/internal/effective-roles` already counts as one
  `resolve` op (noted in T1).

## Commit

`feat(resolve): batch wire — /internal/effective-roles + guarded lookupAll override (T4)` — see git.
