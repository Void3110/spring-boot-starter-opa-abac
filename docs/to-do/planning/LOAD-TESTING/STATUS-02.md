---
tags:
  - status/done
  - type/project
  - area/infra
  - area/architecture
---

# STATUS — T2: Gate-overhead scenario + the two-pass guarded/baseline orchestration (the headline)

**Status:** ✅ DONE (2026-07-08)

## What shipped

- `scripts/load/scenarios/gate-overhead.js` — single `GET /api/v1/catalogs/{loadCatalogId}` as
  `perf`; `constant-arrival-rate` at `RATE`; **validity thresholds only** (`http_req_failed
  rate==0`, `checks rate==1`, `dropped_iterations count==0` — dropped iterations mean the offered
  rate wasn't kept, which in an open model is an invalid window); `maxRedirects: 0` +
  `expectedStatuses(200)` so a silent redirect-to-login can never pass as a fast 200; summary
  export with `med/p(95)/p(99)`.
- Runner orchestration (`run-load.sh`): per-scenario **discarded warm-up invocation** then `REPS`
  measured runs; `guarded`/`baseline` modes run the scenario on the rig as found (state asserted,
  never trusted); **`full`** deploys the canonical posture for BOTH passes (guarded → baseline →
  restore), re-minting and re-warming after every redeploy.
- **The identical-gateway rewire**: `deploy.sh up` with `ENABLE_OPA=0` also drops the gateway's
  coarse allow-all `opa` plugin from the `catalog-all` route — which would have made the delta
  conflate the gateway OPA hop with the app-side library gate, contradicting ADR 0021 §2's "the
  app-side library gate is the only variable". The baseline deploy therefore re-runs
  `infra/apisix/init-routes.sh` with `ENABLE_OPA=1` (the same committed mechanism `deploy.sh`
  uses), and a new **`assert_gateway_posture`** probe (APISIX admin API: `openid-connect` + `opa`
  present on `catalog-all`) runs before every pass — asserted, never trusted.
- **Trap-on-exit restore**: armed before the baseline flip, disarmed only after the explicit
  restore is asserted guarded; a red exit anywhere in the baseline half restores the guarded rig
  (proven in anger — see Integration).
- **Delta computation**: `REPS` medians per stat (p50/95/99), guarded vs baseline, absolute +
  relative, printed and written to `results/<run>/gate-overhead-delta.json`.

## The discovered blocker → PR #61 (the fix-first branch)

The first I2 smoke crashed the baseline pods: **the ADR-pinned `ENABLE_OPA=0` rig had never been
booted**. Three app-side failures with the starter off (`opa.abac.enabled=false` removes the whole
auto-config): the empty `FilterRegistrationBean` fails Tomcat startup (`getDescription()` asserts
non-null even for a disabled registration); nothing can authenticate the forwarded Bearer →
`/api/v1/**`'s `authenticated()` is a 401 wall; and four app components hard-inject starter beans
(`AbacQueryService` ×2, `HierarchicalPathMaintainer`, `OpaAbacProperties`). Per the slice's
zero-app-code invariant this was a genuine mid-run design fork — resolved with the maintainer:
**fix-first branch to `main`** ([PR #61](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/61),
squash `9cea579`), keeping the slice's own diff app-code-free; this branch was then rebased onto it.
The fix gives the example app an honest **unguarded profile**: serve id'd reads on gateway trust,
lists land on their documented fail-closed floor (the empty page), hierarchy writes fail loudly
(reads only), audit posture kept (actuator beyond health `denyAll`), plus `UnguardedBootIT` (real
Tomcat) pinning the contract.

## Tests

- Offline: `bash -n`; the delta computation exercised against real summary exports; k6 scenario
  parse + a live threshold proof — a garbage token makes the validity gate exit non-zero (**99**),
  so a broken window records nothing.
- **I2 green** (`RATE=5 DURATION=15 WARMUP=5 ./run-load.sh full`, exit 0): both passes completed;
  per-pass pod-state probe (`true` then `false` then `true`) + gateway-posture probe all asserted;
  two summary JSONs + warm-up exports + **the delta block** landed in `results/20260708-093929-full/`;
  **the rig ended guarded** (final deploy asserted). `baseline` against a guarded rig aborts red
  **before any load** (re-proven after the T2 changes, exit 1).
- The trap restore **fired in a real red run** (the first smoke's baseline crash): the rig came
  back guarded automatically — the harness's fail-closed edge, observed, not just designed.

## Architecture review + refactor

- **Validity:** every new edge lands red — pod-state mismatch per pass (probe), gateway-posture
  drift between passes (the new admin-API probe), k6 threshold violations (exit-99 proven),
  dropped iterations (threshold), a redirect masquerading as success (`maxRedirects: 0` +
  `expectedStatuses(200)` + body check). **Finding → refactor applied:** the `ENABLE_OPA=0`
  gateway-plugin drop (above) — the single substantive finding; fixed with the init-routes rewire
  + the posture probe. Nothing else needed refactoring.
- **Security:** the flip reuses `deploy.sh`/`init-routes.sh` only; no flag beyond `ENABLE_OPA`
  differs between passes (the canonical posture is deployed for both, so a stray SPA/directory rig
  can't leak into the comparison); restore is trap-on-exit and asserted; the unguarded app profile
  itself was reviewed in PR #61 (gateway still validates tokens; actuator stays denied).
- **Concurrency/idempotency:** the scenario only reads; re-running `full` re-seeds
  deterministically and re-deploys from scratch; `results/` runs are timestamped directories.
- **Wiring:** the delta consumer is `PERFORMANCE.md` (T6); the scenario's request shape is T5's
  input; the posture probe is consumed by every mode; non-happy paths proven (red abort, trap,
  exit-99).
- **Boundary:** this branch's diff stays `scripts/**` + docs + the realm export — the app fix
  lives in `main` via PR #61, not in this slice's diff. `git diff origin/main -- '*.java'` on this
  branch: empty.
- **Pattern reuse / layering:** the runner orchestrates; the scenario only generates load; the
  delta step only reads exports. No layer reaches across.

## Decisions

- **`full` deploys the canonical posture (ENABLE_OIDC=1 ENABLE_USER_SERVICE=1) for both passes**
  rather than mirroring whatever rig it finds: posture detection that misreads would produce a
  silently invalid delta (the validity posture prefers loud), and the delta is only defined for
  same-flag passes. Documented consequence: a `full` run reshapes an SPA/directory rig to
  canonical; redeploy your preferred flags afterwards.
- **The gateway keeps its coarse `opa` plugin in the baseline pass** (the rewire) — the identical-
  gateway invariant is enforced by construction *and* asserted per pass.
- Standalone `baseline` mode demands the exotic posture already be correct (probe red with the
  actionable rewire command, pointing at `full` as the orchestrated path).

## Commit

_(this ticket's commit on feature/void3110/load-testing; see git log)_
