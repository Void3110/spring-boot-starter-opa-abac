---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T4: e2e fault-injecting headline + docs + slice record

**Status:** ✅ DONE — artifacts + docs shipped **and the live two-pass headline PASSED through the gateway**
(E1 → 200, E2 → 403; see *Integration / e2e*).

## What shipped

The e2e headline harness + the guide + the slice record. No production-code change in T4 (proof + docs).

- **`infra/resilience-stub/resolve_stub.py`** — a tiny fault-injecting stand-in for the resolve endpoint
  (`GET /internal/effective-role`). `STUB_MODE=transient` returns `STUB_FAILS` (default 1) consecutive
  `503`s **per caller key** then the resolved editor role; `STUB_MODE=down` returns `503` on every call.
  The smallest thing that injects "N-transient-then-recover" + "stay-down" — stdlib only, no image build.
- **`infra/compose.resilience-stub.yaml`** — runs the stub on `python:3.12-alpine` (the script mounted),
  on the project network as `resolve-stub:8080`. Opt-in.
- **`deploy.sh` wiring** — `ENABLE_RESILIENCE_STUB=1` points the catalog pods' `role-source=http` at
  `http://resolve-stub:8080` (instead of the real user-mgmt) and starts/stops the stub in the up/down
  lifecycle, threading `STUB_MODE`/`STUB_FAILS`.
- **`scripts/postman/resilience-matrix.postman_collection.json`** + **`run-resilience-matrix.sh`** — the
  matrix asserts the cut in **two passes over one rig**: **E1** (stub `transient`) → the protected id'd
  `GET /api/v1/categories/{id}` **succeeds (200)** — the resolve guard rode out the blip; **E2** (stub
  `down`) → it **still denies (403)** — B2's wall, and the collection additionally asserts the response is
  *never* a 2xx (no realm-fallback widening rode the outage). The runner mints the token **in-network**
  (issuer caveat), seeds the `cccc…` fixture, and flips the stub mode between passes.
- **Docs:** `docs/guides/HTTP-RESILIENCE.md` (new — the mechanism: the three edges, the `CallGuard` seam,
  classification, asymmetric budgets, breaker outcome-invariance, the fail-closed contract incl. the
  `error()`-not-`denyAll()` landmine, the kill-switch, the optional-R4j wiring, the Boot-4-swap forward
  note). Cross-refs reconciled: [[PARTIAL-EVALUATION-FILTERING]] (the `fromError` suppression the OPA
  decorator must preserve on a breaker-open `compile`) + [[B2-SUPPLIER-OUTAGE]] (the wall this softens; the
  kill-switch inverse). `infra/README.md` (the B3 opt-in section + the stub in Pieces);
  `scripts/postman/README.md` (the new matrix + the `cccc…` fixture-registry row).

## Tests

- **`opa test infra/opa/policies/`** → **183/183 PASS** — unchanged (zero Rego this slice, as designed).
- **The fault-injecting stub** is unit-validated standalone: `transient` mode returns `503` then `200`+role
  per caller key; a fresh key gets its own sequence; `down` mode returns `503` every call.
- **The collection** parses and runs under newman (the cut assertion + the never-2xx fail-closed guard for
  the E2 pass).
- **`deploy.sh` + `compose.resilience-stub.yaml`** pass `bash -n` / `docker compose config`.

## Architecture review + refactor

_Filled at the ★ gate._ Findings:
- **No production-code change in T4** — it is proof + docs, so the review is over the harness, not the
  library. The stub is read-only and the catalog points at it only under the opt-in `ENABLE_RESILIENCE_STUB`
  flag (default off ⇒ the rig is byte-identical to pre-B3).
- **The cut, not the shape:** the E2 pass asserts a clean `403` **and** explicitly `not within(200,299)` —
  so a regression that let a sustained outage ride the realm fallback to a 2xx would fail the matrix, not
  pass it. E1 asserts the recovered `200`. The contrast is the headline.
- **Zero Rego confirmed** — `git diff --name-only main...HEAD -- '*.rego'` is empty; `opa test` count
  unchanged at 183.
- Nothing to refactor — no invented churn.

## Integration / e2e

- **The live two-pass gateway run PASSED this session.** Rig: `./profile.sh up` then
  `ENABLE_OIDC=1 ENABLE_RESILIENCE_STUB=1 ./deploy.sh up --pods 2` (fresh image), all 9 containers healthy
  (incl. `resolve-stub`). `scripts/postman/run-resilience-matrix.sh` →
  - **E1** (stub `transient`, 1×503 then the role): `GET /api/v1/catalogs/{root}/categories/{id}` → **200**
    — the resolve `CallGuard` rode out the blip, the gate resolved the role, the request succeeded.
  - **E2** (stub `down`): the **same** request → **403** — the guard exhausted, `HttpRoleDefinitionSupplier`
    threw `RoleResolutionException`, the gate denied. The collection's extra guard (`not within(200,299)`)
    confirms no realm-fallback widening rode the outage to a 2xx. **0 assertions failed across both passes.**
- **Deterministic backstop** (also green): E1 by `ResilientOpaClientTest.allow_recoversWithinBudget` (OPA) +
  `EdgeResilienceTest.resolve_transientThenRecovers` (resolve); E2 by
  `EdgeResilienceTest.resolve_exhaustedTransientThrows` → `RoleResolutionException`.
- **E3 no-regression** is covered by the full catalog IT suite (87) green with resilience **on at defaults**
  (T3) — B3 is transparent on the happy path by construction (resilience wraps the same calls). The existing
  e2e matrices need a non-stub rig config (real user-service / demo role-source), so they are not re-run in
  the stub-pointed resilience rig; the IT suite is the no-regression proof. `opa test` 183/183 unchanged.
- **Fixture schema fix found live:** the runner's psql seed needed the NOT-NULL `created_at` + `tags` +
  the ltree `path` columns (the first naive seed failed); corrected in `run-resilience-matrix.sh`. The
  collection URL is the real route `/api/v1/catalogs/{catalogId}/categories/{categoryId}` (not a flat
  `/categories/{id}`).

## Decisions

- **A small stub over the existing harness** (the T4 open question): the existing newman/`deploy.sh` harness
  has **no** failure-injection toggle, and adding env-toggled failures to the real user-mgmt/OPA would mean
  modifying production rig services. The smallest fault injector is a stdlib stub the catalog's `role-source`
  points at — no image build, opt-in, removed cleanly on `down`. Documented in `infra/README.md`.
- **Two passes over one rig** (transient then down) rather than two rigs — the stub is recreated in each
  mode between passes, so a single stand-up proves both halves of the contrast.
- **Resolve-edge fault injection** (not OPA) — the resolve edge is the cleanest gateway-observable headline
  (an id'd protected request resolves the role through the fault-injected edge; the OPA edge's recovery is
  already pinned at unit level by `ResilientOpaClientTest`).

## Commit

`feat(resilience): e2e fault-injecting headline + HTTP-RESILIENCE guide + slice record (T4)`
