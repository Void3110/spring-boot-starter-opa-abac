---
tags:
  - status/active
  - type/review
  - area/abac
  - area/security
  - area/opa
  - slice/B4
---

# Multi-tenant isolation (Slice B4) — Code Review

> **Verdict**: Approved with fixes (all applied on-branch).
> **Scope**: The full Slice B4 (T1–T9) — membership-as-sole-access-path catalog isolation + safe
> self-service (create catalog/team/members) with a cross-service ownership squat-gate, the realm-fallback
> removal across three policies, and the T9 type-level-gate role-resolution seam.
> **Branch**: `feature/void3110/multi-tenant-isolation` vs `main` (79 files, +4292/−557).
> **Method**: multi-lens adversarial workflow (8 failure-mode lenses → adversarial refutation →
> completeness critic → synthesis), then maintainer spot-verification of each survivor from source.

## Summary

A strong autonomous slice. **Zero Critical findings** — the load-bearing invariants all held:
`opa-abac-core` stays Spring-free (untouched by the diff); the catalog list residual is governed-scope
**∧** filter-residual, fail-closed to an empty page (never a bare `findAll`); the realm fallback is
removed unconditionally with only the narrow pre-membership `catalog:create` retained; the squat-gate
denies by default (resolver-absent / null-subject / not-owner all → 403). The 8-lens fan-out + adversarial
refutation surfaced **4 confirmed findings (2 Medium, 2 Low), 0 refuted** — refutation killed nothing,
and the completeness critic added the two test-gap findings no single lens generated. All four are fixed
on-branch.

## Critical Issues

None.

## Medium Issues

### M1 — `/internal/**` reachable through the gateway catch-all (SECURITY) — FIXED
The catalog APISIX route is a catch-all (`"uri": "/*"`, priority 0). A request to
`GET /internal/catalog/{id}/created-by` matched it and proxied to the catalog pod, whose `SecurityConfig`
`permitAll()`s `/internal/**` — leaking the catalog's creator `sub` to any authenticated caller (or anyone
when `ENABLE_OIDC=0`). The user-service `/internal` is protected by **omission** (its pool has only
`/api/v1/teams*` + `/api/v1/users*` routes), but the catalog catch-all had no such protection, directly
contradicting the "never gateway-fronted" invariant asserted in `SecurityConfig`,
`InternalOwnershipController`, and `init-routes.sh` itself. The gateway `opa` plugin uses the
`gateway` policy = unconditional `allow := true`, so it did not block it.

**Fix** (`infra/apisix/init-routes.sh`): an explicit `internal-blocked` route, `uri=/internal/*`,
**priority 70** (> usermgmt's 60 > the catch-all's 0), with a `serverless-pre-function` (already enabled
in `config.yaml`) that `core.response.exit(404)`s before any upstream is selected — making the invariant
**structural** (a positive deny route) rather than incidental (a missing route). The misleading
"there is NO /internal route here" comment was corrected to describe the positive block.

**Verified live** (rig up): every `/internal/*` path through `:9085` → **404** (catalog created-by,
governed-targets, bootstrap, effective-role); the public `/api/v1/catalogs` + `/api/v1/teams` still route
(302 oidc-redirect, not the 404 block) — the block does not over-match.

### M2 — the explicit-null-id type-level wire shape is untested (TEST_GAP) — FIXED
The load-bearing T9 null-safety fix added a two-body helper `is_type_level_request if not
input.resource.id` **OR** `is_type_level_request if input.resource.id == null`, because Rego
`not input.resource.id` is **UNDEFINED (not true)** for an explicit `null`. But every type-level test used
the id-**omitted** shape `{"type":"category"}`, exercising only clause 1 — the `== null` clause (the actual
fix) was never reached. Yet the production wire shape **is** explicit null: `AbacContext.Resource.id` has
no `@JsonInclude(NON_NULL)`, and the type-level callers pass `new AbacContext.Resource(type, null, …)`.
Deleting the `== null` clause kept `opa test` green at 197/197 while silently denying every member's
type-level create/list/assign-tags in production.

**Empirically confirmed**: `opa eval` of `data.category.allow` on the real `{"type":"category","id":null,…}`
wire shape returns **true** with the clause and **false** without it (clause 1 alone does not cover
explicit null — verified on opa 1.10.1).

**Fix** (`category_test.rego`, `product_test.rego`): added `*_null_id` helpers using the real
`"id": null` wire shape + grant-for-member and deny-for-non-member tests. `opa test` 197 → **202**. Proven
to regression-guard: removing the `== null` clause now flips these tests red.
**Sibling sweep**: `catalog.rego` has **no** `is_type_level_request` clause (its `create` rides the narrow
realm fallback, its list rides `filter`) — there is no null-id-dependent path there, so no catalog null-id
test is needed (correctly excluded, not missed).

## Low Issues

### L1 — `createTeam` 403 undocumented in the OpenAPI spec (API_CONTRACT) — FIXED
The squat-gate makes `POST /api/v1/teams` answer 403, but the operation declared only 201/400/409.
**Fix** (`user-mgmt-api.yaml`): added `'403': { $ref: '#/components/responses/Forbidden' }`, mirroring
`addMember`/`transferOwnership`.

### L2 — the ownership guard's off-states lacked an e2e 403 test (TEST_GAP) — FIXED
`OwnershipGateIT` covered not-owner (I7) and unverifiable (I8, via `isOwner=false`) but not the guard's
other two deny disjuncts: `subject == null` and `resolver == null`. **Fix**: added `noSubjectIsDenied`
(I8b) — a request with no resolvable subject, while the ownership decision **would allow**, still 403s
(pinning the authn-edge-defaults-to-deny branch). The `resolver == null` branch is covered by inspection
(the guard's first disjunct; the production default the `@TestConfiguration` comment already documents) —
exercising it would require excluding the `@Primary` test bean (a separate context), disproportionate for
a one-line guard already pinned on its sibling disjunct.

## Fail-closed verification

Every error/empty path traced to deny/empty:
- **Catalog list** (`CatalogListAuthorizer`): governed-scope **∧** filter-residual; no governed ids → empty
  scope → empty page; a role that denies `list` → `filter` compiles to `DENY_ALL` → empty. Never `findAll`.
- **Type-level gates** (category/product `list`/`create`/`assign-tags`): a non-member resolves no
  role_definition → `list_inheritable_grant` fails → deny. The `roleResource` override, when declared but
  unresolvable, returns a null `ResolvedCheck` → deny (manager fail-closed). Confirmed unchanged by the
  lenses; M2's test now guards the null-id path.
- **Single-resource** (`allow`): the blanket realm fallback removed; no role_definition → deny at every
  verb (the deep-link leak closed).
- **Ownership squat-gate**: `resolver == null || subject == null || !isOwner` → 403; the resolver returns
  `false` (never throws) on unknown-type / owner-service-down / 404. All collapse to 403.

## Security audit

- **IDOR/info-disclosure**: M1 (the `/internal` creator-sub leak) was the one real instance — closed.
- **Fallback interplay**: the only surviving realm fallback is the verb-gated `catalog:create`
  (pre-membership onboarding); it cannot widen any id'd or list decision (verb `== "create"` only). Sound.
- **`/internal/**` boundary**: now positively blocked at the gateway (M1) **and** `permitAll` +
  in-network only at the app — defense in depth.
- **Injection / secrets / cache**: no SpEL/SQL/JSONB/ltree built from user input on the changed paths; the
  ownership TTL cache keys on `(type, id)` (subject-independent by design — it answers "who created X", not
  "may S act") so no cross-subject authz artifact is served; no secrets/internal state added to logs/bodies.

## Concurrency & idempotency

- `createWithOwner` is the existing atomic owner-on-create path (unchanged by B4). The ownership check is a
  pre-write read of an immutable fact (`created_by`, set once at creation) — no decide-then-act race: the
  creator id cannot change between check and bind. The `uq_team_target` constraint makes a double-create
  converge to 409, not a duplicate. The isolation e2e's non-idempotency is a test-fixture property (now
  self-reset), not a production concern.

## Wiring & sibling sweep

- Every new seam has a non-test caller + a non-happy-path test: `roleResource` override (controllers +
  TagDecisionGate; +2 security unit tests incl. the unresolvable-deny), `GovernedScopeResolver`
  (`HttpGovernedScopeResolver` + ITs), `ResourceOwnershipResolver` (`DiscoveryOwnershipResolver` + the
  squat-gate IT), the `internal-blocked` route (live-verified). No zero-caller seams found.
- **Sweeps performed**: category.rego ↔ product.rego (both got the null-id test; catalog correctly has no
  such clause); the three controllers' type-level gates (all carry the override); the two example services'
  `/internal` exposure (user-service safe-by-omission, catalog now safe-by-block).

## Autonomous-run check

- **Laziness**: none material — but M2 is the signature autonomous miss (a test asserted the *shape* of the
  type-level gate via the omitted-id input, not the *production cut* via the null-id wire shape). Now fixed.
- **Self-preferential bias**: STATUS-09 honestly recorded the three regressions the e2e surfaced and the
  honest assertion flips; no "review found nothing" mismatch.
- **Goal drift**: fail-closed held across all 9 tickets; core stayed Spring-free; the residual ANDs (never
  replaces) the scope. The one drift-shaped gap (M1) was a *gateway-config* omission, not an erosion of a
  code invariant — the app-layer `/internal` posture was correct throughout.

## Test results

- `opa check --strict infra/opa/policies/`: clean
- `opa test infra/opa/policies/`: **202/202** (was 197; +5 null-id regression tests)
- `./gradlew build`: green (incl. the new `OwnershipGateIT.noSubjectIsDenied` + OpenAPI 403 codegen)
- Live gateway verification (M1): all `/internal/*` → 404; public `/api/v1/*` still route.
