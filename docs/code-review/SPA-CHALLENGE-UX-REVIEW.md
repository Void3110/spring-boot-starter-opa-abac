---
tags:
  - status/active
  - type/review
  - area/abac
  - area/catalog-service
---

# SPA-CHALLENGE-UX — Code Review

> **Verdict**: **Approved with fixes** — 9 findings, **0 Critical**, all fixed on the branch.
> **Scope**: the demo console consuming the RFC 9470 step-up challenge (locked panel, [Verify]
> round trip, elevation chip, provenance badges) plus the `_provenance` carrier on the catalog
> service. · **Branch**: `feature/void3110/spa-challenge-ux` vs `main` — 46 files, +4984/−129.

## Summary

Multi-lens adversarial review: 8 specialist lenses (fail-closed/authz, core-boundary, rego-policy,
persistence-concurrency, security-audit, api-contract, conflict/CI/dead-code, infra-e2e), every
candidate finding put to a skeptic that tried to refute it from source, then a completeness critic
raising what refutation structurally cannot. 21 agents, 0 errors. **9 confirmed, 2 refuted.**

**The headline is a negative result.** The review was pointed explicitly at the highest-risk surface
this slice introduces — `CatalogProvenanceMemo`, a **request-scoped memo carrying per-subject
authorization provenance**, which is textbook "a cache serving an authz artifact across
subjects/requests" — plus the two-leg supervised partition and the enrichment fail-closed path.
**Nothing was confirmed there.** The memo's lifecycle, its request binding, the partition's inability
to leak a catalog into a page that should not hold it, and the degrade branches all held under a lens
whose entire job was to break them.

What the review did find is concentrated in the **test and CI layer**: three of the new e2e cells
assert less than their names claim, and the 63 new vitest tests were not wired into CI at all. That
is a fair characterisation of this slice's real weak spot — it shipped a lot of new *verification*,
and the verification itself was the least verified thing in it.

## Critical Issues

**None.**

## Medium Issues

| # | Finding | Fix |
|---|---|---|
| 1–2 | **`E32b` is structurally vacuous.** The cell guards "the demo world never leaked into a MATRIX supervisor's page", but the runner only reaches it *after* both matrices tear down their fixtures — and those teardowns delete every reporting edge and team seat `sup-anna` has. `CatalogListAuthorizer.readable()` short-circuits to `Page.empty(...)`, so `ids` is `[]` and all three `not.include` assertions pass on an empty array. Found independently by two lenses. | Added a **positive control**: `count === 0`, with a comment explaining why zero is the correct expectation here and why the absence checks alone assert nothing. A leaked row now fails on the count. |
| 3 | **`E31j` asserts half its own name.** "the category **and product** ids survived the re-seed" only ever read `/categories`; no product id was pinned and no products request existed — a seed that recreated products on every run would have passed it. | Runner now pins `PRE_PROD_PRODUCT_ID` before the re-seed; new cell **`E31k`** asserts count 1 + unchanged id. Verified green on the rig. |

## Low Issues

| # | Finding | Fix |
|---|---|---|
| 4 | `CatalogDetail` used `full.data?._provenance ?? catalog._provenance` — once `full` has loaded, an **absent** provenance means the server declined to compute one, and the `??` re-asserted the (possibly stale) grid row's label instead. | `full.data ? full.data._provenance : catalog._provenance` — fall back only while `full` has not arrived. |
| 5 | **The 63 new vitest tests never ran in CI.** `example-demo-ui` is not a Gradle module, so `./gradlew build` never sees it; no job covered it. | New `demo-ui` job in `.github/workflows/ci.yml` (setup-node + `npm ci` + lint + test). |
| 6 | `run-demo-world-matrix.sh` lacked the `ENV_FILE` existence preflight its siblings have — a missing env file surfaced as an opaque newman error *after* the TOTP mints were spent. | Guard added, matching the sibling wording. |
| 7 | The **freshness-drill guard was on 1 of 3 challenge cells.** `E32c` pinned `max_age` to the shipped window; `E31d` only asserted `>= 0` and `E33c` never parsed it — so `--skip-matrices` and `--convergence`, both separate entry points, would certify a rig still holding the drill's `max_age=5`. | Copied the shipped-window assertion into `E31d` and `E33c` (the env-var already reached both folders). |
| 8 | **The restore path contradicted its own comment and the README.** Line 206 promises "a challenged category simply leaves the user on the catalog with the panel", but a `StepUpRequiredError` from `getCategory` hit the outer `catch` and dropped the user to the **grid** — one level further out than the verification they just completed. | The category read is now caught separately: a `StepUpRequiredError` lands on `{kind:'catalog'}` so the catalog's own `cats` load re-raises the challenge and renders the passive panel. A non-challenge error still rethrows to the outer catch, which remains correct for the metadata-only catalog read. |
| 9 | QA case **I2**'s last sub-case (memo-less page keeps `_actions` while `_provenance` is absent) mapped to no test — `u7_noMemoOmitsTheFieldEntirely` asserts only the absence, so a version that rebuilt items and dropped `_actions` would still pass it. | Added `u7_noMemoOmitsProvenanceButLeavesTheREST_ofTheEnrichmentIntact`. |

## Refuted / dropped

- *"Developer-machine workspace path committed to the public repo in a Mulch record"* — **independently re-checked** given the IP boundary: `git grep "/Users/dmitry" -- .mulch` and a sweep for proprietary workspace/product names across `.mulch`, `docs`, `example-demo-ui` both return nothing. Correctly refuted.
- *"`stepUpStateOf`'s reject branch has no test"* — refuted; the branch is covered.

## Fail-closed verification

Every error/empty path on the touched decision surface confirmed to land on deny/empty:
`CatalogListAuthorizer.readable()` returns `Page.empty(pageable)` when both membership and
supervised sets are empty (verified directly while confirming finding #1); the advice's role-source
outage is swallowed and **omits** the label rather than guessing one
(`u8_aRoleSourceOutageIsSwallowedAndTheBodyIsOtherwiseIntact`); a subject-less request looks nothing
up and the field is absent; an unparseable or half-formed challenge yields `null` from
`parseChallenge`, which the console renders as a **plain** error, never a [Verify] that cannot work
— proven on the wire in T6 across 7 corruption variants, not just in unit tests.

## Security audit

No confirmed finding. Specifically checked and clean: the provenance memo is request-scoped and does
not serve one subject's authorization artifact to another (the lens targeted at this found nothing);
`_provenance` cannot label a catalog into a page the subject may not see, because the label is
stamped onto rows the authorizer already scoped; no secrets, corporate names, hosts, or ids in the
seed script or realm export; no local filesystem paths in tracked files.

## Concurrency & idempotency

No confirmed finding on the decide-under-protection or version-binding invariants — this slice adds
no mutation path. The **idempotency** gap found was in the *test* asserting it (#3), not in the seed:
the seed is genuinely find-or-create, and `E31k` now proves that for products as it already did for
catalogs and categories.

## Wiring & sibling sweep

- **#6 swept across all 19 `scripts/postman/run-*.sh`.** Two runners flagged by the grep were
  **false positives**: `run-resilience-matrix.sh` passes only `--env-var`s and
  `run-spa-auth-smoke.sh` is pure curl — neither consumes an env file. `run-demo-world-matrix.sh`
  was the only genuine gap. **Siblings clean.**
- **#7 swept across all three challenge cells** in the collection (`E31d`, `E32c`, `E33c`) — that
  sweep *is* the fix.
- **#5 swept for other non-Gradle suites**: `example-demo-ui` is the only `package.json` with a
  `test` script. **Siblings clean.**
- **#8 swept for other broad catches landing on the grid**: the one at line 244 is the only such
  catch and is intentional (a failed *catalog* read really is a hard failure). **Siblings clean.**
- **#4**: the adjacent `env: (full.data ?? catalog).tags?.env` is **not** the same defect — its `??`
  selects the *object*, not the field, so a loaded-but-untagged catalog already resolves correctly.

## Autonomous-run check

**Not applicable in the usual sense** — this slice was a deliberately *collaborative* build (the
roadmap decision), so there is no autonomous run to audit for laziness or drift. The equivalent
check on the STATUS notes: they do **not** overclaim. STATUS-03 and STATUS-04 each explicitly record
their unfinished cells rather than declaring green, and STATUS-06 records E12(b) as unreachable with
its ruled-out routes instead of quietly dropping it. The one place the paper trail *was* wrong is
recorded in STATUS-06 itself: three cells were parked on a false premise about tooling, which T6
overturned.

## What's done right

- The `_provenance` degrade branches are exhaustively tested (11 advice tests) and, notably, **omit
  rather than guess** on every unknown state — the honest choice, and the one that keeps the console
  from rendering a badge the server never authorised.
- `parseChallenge` handles the full RFC 7235 `auth-param` grammar rather than splitting on commas,
  with a **deliberate, commented** quoted-pair branch kept specifically so a later "simplification"
  cannot collapse the parser down to whatever the current emitter happens to emit.
- The `stepUp.maxAge` lifecycle asymmetry — cleared in `login()`/`logout()`, pointedly **not** in
  `stepUp()` — is load-bearing in two directions and is pinned by a unit test, which is exactly how a
  future tidy-up gets stopped.
- The console never pre-empts the server: T6 measured it rendering contents the server allowed while
  its own prediction stayed honestly amber.

## Test results

| gate | result |
|---|---|
| `./gradlew build` | ✅ BUILD SUCCESSFUL (incl. the new advice test; 11/11 in `CatalogProvenanceAdviceTest`) |
| `opa test` | ✅ 389/389 (no policy change in this slice) |
| `./.sonar-local/sonar-local.sh` | ✅ **CLEAN — 0 open findings** on changed files |
| `npm run lint` · `npm test` · `npm run build` | ✅ · ✅ **63 tests** · ✅ |
| `run-demo-world-matrix.sh` | ✅ **0 failures**, incl. the new `E31k` and the E32b positive control |
| `run-tests.sh` (smoke) | ✅ 22 |
| E10–E21 Browser-pane pass | ✅ STATUS-06 |

`E33c`'s new assertion runs only under `--convergence` (a separate entry point after a realm
re-import + re-seed) and so was **not** exercised on a live run here; its script was extracted and
syntax-checked with `node --check` instead. Stated rather than implied.
