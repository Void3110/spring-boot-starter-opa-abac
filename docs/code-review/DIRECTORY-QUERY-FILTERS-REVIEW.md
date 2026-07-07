---
tags:
  - status/active
  - type/review
  - area/api
  - area/spring
---

# DIRECTORY-QUERY-FILTERS (Slice 1) — Code Review

> **Verdict**: Approved with fixes (one Medium, found outside the diff by the sibling sweep)
> **Scope**: The Phase-7 Slice-1 autonomous run — exact-match query filters (`?subject` on `/users`,
> `?targetType`+`?targetId` on `/teams`), the 204/`Accept` `produces` fix, the bootstrap
> `displayName` upsert, e2e + SPA one-shot adoption + docs.
> **Branch**: `feature/void3110/directory-query-filters` vs `main` (34 files, +1560/−164, 5 tickets)

## Summary

Multi-lens adversarial review (8 lenses — fail-closed/authz, security-audit, persistence/concurrency,
core-boundary, rego, API-contract, conflict/CI/dead-code, infra-e2e — plus per-finding adversarial
refutation and a completeness critic; 11 agents). **Zero defects found in the slice's own diff.** The
completeness critic produced the review's one confirmed finding — an **unswept sibling** of T3's fix
in the *other* example service — consistent with the recorded pattern that refutation narrows while
only the critic widens (mx-98fc1a, mx-27b7c7).

## Critical Issues

None.

## Medium Issues

| # | Issue | Status |
|---|---|---|
| 1 | **Unswept sibling of T3:** the catalog service's three 204-only DELETE ops (`deleteCatalog`, `deleteCategory`, `deleteProduct` — `catalog-api.yaml` had `'204': description: Deleted` with no success content) generated `produces={application/problem+json}` and therefore **406'd a bare `Accept: application/json`** — the exact defect class T3 fixed in user-mgmt, live on gateway-reachable, `@OpaPreAuthorize`-gated endpoints. | **Fixed** — the same schema-less `NoContent` response component added to `catalog-api.yaml`, all three 204s `$ref` it; regenerated `produces` widens, controller signatures stay `ResponseEntity<Void>`. Proven by the mirrored `NoContentAcceptIT` (catalog): fails `406-for-204` against the pre-fix spec (negative-control run), green post-fix; the no-`Accept` guard passes both ways. |

## Fail-closed verification

No OPA decision, residual, rego, or `OpaClient` path is touched — the fail-closed lens confirmed no
error/empty branch anywhere in the diff can widen access. The slice's own no-widening invariant
(filter miss ⇒ empty page; half-specified pair ⇒ 400 **before any repository access**; absent filter ⇒
byte-for-byte `findAll`) is pinned at three layers: unit (U1/U2 assert the untaken repository method is
never invoked), IT (I1/I2 through the real chain), e2e (E1/E2 negatives through the gateway).

## Security audit

Clean. The new filter params narrow only (no scope/ownership check weakened — `listUsers`/`listTeams`
keep their prior authenticated-read posture; `?subject` reveals nothing the unfiltered list didn't
already enumerate to the same caller). The bootstrap upsert writes **only `displayName`** on the
same-subject row (subject/id/memberships/roles untouchable by construction — the method uses only the
`users` repository) and stays on the gateway-blocked `/internal/**` seam; the e2e collection itself
demonstrates that boundary (reads ride `{{gateway}}`, the seed rides `{{user_service}}`). No injection
surface (parameters bind through Spring conversion + derived-query finders; no string-built queries).
No secrets/log leakage.

## Concurrency & idempotency

The read filters are pure reads. The upsert is `@Transactional`, converges on re-post (identical ⇒
no-op with no save issued; changed ⇒ same row, new value — I4 A→B→B), and the subject unique
constraint backstops the pre-existing concurrent-first-seed race (unchanged by this slice; the seed
script is sequential). No gate-time-snapshot / version-binding surface exists in this diff.

## Wiring & sibling sweep

Every new seam has a named consumer and a non-happy-path test: the two filters (SPA `ensureUser` +
`TeamPanel`; miss/half-specified negatives at all three layers), `PageDefaults.onePage` (both
controllers; past-the-end unit case), the upsert (`seed-demo-data.sh` + E4). Dead code: `listAllTeams`
and the then-caller-less `listTeams` removed (grep-verified; `api.ts` keeps no speculative helpers).
**Sibling sweep result: one hit** — the catalog 204 ops (fixed above; the user-mgmt side already
covered all four of its 204 ops in T3). No other OpenAPI spec exists in the repo; sweep closed.

## Autonomous-run check

- **Laziness** — not observed: every ticket's acceptance cases exist and assert the *cut* (row counts,
  ids, status codes), not just shape; T3 and the review fix both carry executed negative-control runs.
- **Self-preferential bias** — not observed: STATUS notes match the diff (the two "nothing substantive"
  ★ reviews correspond to genuinely minimal changes; real decisions — past-the-end, blank-as-absent,
  schema-less content — are recorded as decisions, not silence).
- **Goal drift** — not observed: no library/rego/schema change (verified by lens, not just claimed);
  the additive-branch invariant held across T1/T2. The one miss was **scope-shaped, not drift-shaped**:
  T3's ticket scoped the fix to the user-service, so the run never looked at the catalog's mirrored
  ops — exactly the class the deep-review sibling sweep exists to catch.

## What's done right

The XOR guard throws before any repository access (the full-list fallthrough is structurally
impossible, not merely untested); the envelope semantics are defined once (`PageDefaults.onePage`)
and reuse ADR 0012's past-the-end rule; the 400 rides the existing `VALIDATION_FAILED` vocabulary
(no new error code); the negative-control proof pattern (revert spec → watch the test fail → restore)
was used for both `produces` fixes; the e2e asserts ids and counts, not shapes.

## Test results

- `./gradlew build` (all modules, JDK 21, Testcontainers real Postgres): **green** — including the new
  catalog `NoContentAcceptIT` (2/2; negative-control run fails 406-for-204 pre-fix as intended).
- User-mgmt module suite: 194 tests, 0 failures. `opa test`: n/a (no policy touched).
- Newman `run-team-matrix.sh` through the rig: **17 requests, 20 assertions, 0 failed** (the 9
  pre-existing matrix requests double as the regression check on the new usermgmt image).
- The catalog `produces` fix is proven at the MockMvc layer (content negotiation lives in Spring MVC;
  the gateway passes `Accept` through — the user-mgmt E3 already proves the class end-to-end).
