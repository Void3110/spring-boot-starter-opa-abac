---
tags:
  - status/active
  - type/review
  - area/abac
  - area/catalog-service
---

# SPA-CHALLENGE-UX — Code Review

> **Verdict**: **Approved with fixes** — three rounds, **27 findings, 0 Critical**, all fixed.
> Round 1: 9, in the slice. Round 2: 7, **all in round 1's fixes**. Round 3: 11, **all in
> round 2's fixes** — including a **fail-open I introduced into the clean-room gate itself**.
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

- *"Developer-machine workspace path committed to the public repo in a Mulch record"* — **refuted in round 1, then CONFIRMED in the verify round, and the refutation was mine.** I re-checked it with a pattern matching only the absolute home form, which cannot match the **tilde** form the records actually used; the verify round caught both the leak and my too-narrow check. Two records carried it (one new here, one pre-existing from 2026-08-01) and so did this note's own first draft — while that draft asserted no such paths were tracked. All three reworded; the clean-room gate widened (below). The lesson is the finding: *a refutation is only as good as its pattern*, and a grep that returns nothing is evidence about the grep as much as about the tree.
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

### Two fixes that carry no live proof — stated, not implied

- **`E33c`'s new assertion** runs only under `--convergence` (a separate entry point after a realm
  re-import + re-seed) and so was **not** exercised on a live run here; its script was extracted and
  syntax-checked with `node --check` instead.
- **The restore-path fix (#8): its HAPPY path is now proven live; its defensive branch is not.**
  The fix restructured the restoration effect into a nested `try/catch`, and *both* paths run
  through that new nesting. A post-fix regression pass in the Browser pane (below) drove the
  category-level round trip end to end and confirmed the happy path is intact — [Verify] still
  lands back on **that category** with its product, not on the catalog and not on the grid. What
  remains unproven is the **challenged** branch, entered only when the restored `getCategory` is
  *still* refused after a completed verification: that is precisely the **E12(b) state STATUS-06
  records as structurally unreachable**, with five routes ruled out, and `example-demo-ui` has no
  DOM test environment to unit-test it in. So: the restructuring is verified, the defensive arm
  inside it is still **verified by reading**. Adding jsdom + a component test is the honest way to
  close the remainder, and is a recorded follow-up.

### Post-fix regression pass in the Browser pane (2026-08-18)

The E10–E21 pass in STATUS-06 predates the round-1 fixes, two of which changed `App.tsx`. Those
changes were therefore shipped on unit tests alone until this pass. Re-run against the deployed
bundle (`index-Cc8QhXJO.js`, confirmed to be the post-fix build), scoped to the changed code paths
rather than repeating the whole matrix:

| # | cell | result |
|---|---|---|
| R1 | E10 catalog round trip | ✅ landed back on the same catalog, categories rendered, chip `Elevated · 4:51` |
| **R2** | **E10 category round trip** | ✅ **the decisive one** — challenged at the category level, [Verify] → back on **that category** with its product; not the catalog, not the grid |
| R3 | E17(d) badges, grid + detail | ✅ `supervised` on both, amber on `…0002` only; detail card labels correctly after `full` loads (the `#4` change) |
| R4 | E16 reload | ✅ grid, session restored, chip present, learned window survived |
| — | E17(a) honesty gap | ✅ re-reproduced: contents rendered while the chip stayed honestly amber on a stale learned window |
| — | logout clears the window | ✅ `stepUp.maxAge` null after "Switch identity" (T4's fix still holds) |
| R6 | E15 `pm-demo` | ✅ no chip, no supervised badge, neutral `production`, production contents with no ceremony |

**No regressions.** The drill used for R2 (`max_age=5`) was restored by restarting `opa-abac-opa`
and re-verified with a real decision probe: `max_age 300`, `skew 30`, `required_acr aal2`, `loa`
intact.

### The verify round, and what it says about round 1

The skill's rule — *a round that fixed something is never terminal* — paid for itself. The verify
round found **7 findings, every one of them in the round-1 fixes**, including two that matter:

- **The fix for #9 was itself vacuous**, in the same shape as the finding it fixed: it asserted on
  the *input* page rather than on what the advice *returned*, so the exact "rebuilt items"
  implementation its own comment named would still pass. The skeptic proved it by **mutating
  `CatalogProvenanceAdvice` to return a rebuilt page and watching all 11 tests stay green**. I
  reproduced that mutation after fixing it: the test now fails, and is the **only** one that does.
- **A refutation I made in round 1 was wrong.** I re-checked the "developer path in a Mulch record"
  finding with a pattern that could only match the absolute `/Users/...` form, and the records used
  the **tilde** form — so my grep returned nothing and I called it refuted. Two records carried it,
  one of them pre-existing since 2026-08-01, and this note's own first draft carried it too while
  asserting the opposite. A prior review (`AGENT-TOOL-AUTHZ-REVIEW` #10) had already "fixed" this
  class once; it recurred because the clean-room gate's pattern lacked the tilde form **and never
  scanned `.mulch`, which is committed**. Both holes are now closed in `verify-package.sh`.

The durable lesson is the second one: **a grep that returns nothing is evidence about the grep as
much as about the tree**, and a refutation is only as good as its pattern.

### Round 3 — the gate I wrote to close a leak was itself fail-open

Round 2 fixed the clean-room recurrence by widening `verify-package.sh`. Round 3 found that fix
**fail-open**, and proved it the hard way: it planted a real machine-local-path leak in a Mulch
record, stubbed `git` to exit 127, and watched the gate print **PACKAGE OK, rc=0**.

The cause is the sharpest thing in this whole review. I rooted the new scan at
`git rev-parse --show-toplevel`, discarding stderr and never checking the status — so with git
unavailable the path became the literal `/.mulch`, `[ -d ]` was false, the scan silently did
nothing, and the success line still claimed *"committed .mulch records"* were covered. The script's
own preamble forbids exactly this: it computes a git-independent `$REPO_ROOT` **because** "sessions
and subagents may start OUTSIDE the repo (where a git-rev-parse preamble fatals)". I reintroduced
the dependency the file was written to avoid, ten lines below the comment saying not to.

Round 3's other findings were the same shape — **incomplete sweeps of my own fixes**: the `|| true`
repair reached 3 of the 10 substitutions whose guards it needed to make reachable (`SHIPPED_MAX_AGE`
and every token mint were still unreachable, so a container-runtime failure skipped
`require_token`'s diagnostic entirely); dropping `-i` for the `.mulch` scan silently weakened the
**private blocklist** — codenames and internal hostnames, the highest-value half of the pattern, and
the half most likely to be written in mixed case; and `docs/` — the third site of the original leak —
was still outside every gate.

**What changed structurally, not just textually:**

- The wide scan is rooted at `$REPO_ROOT` and **fails closed** when a tree is missing.
- Home paths are matched case-**sensitively** (so `/api/v1/users/search` does not false-positive);
  tokens and the private blocklist case-**insensitively** (so prose casing cannot hide a codename).
- `docs/` is scanned alongside `.mulch/`.
- A **`clean-room` CI job** now runs on every push and PR. This is the real fix for the recurrence:
  `verify-package.sh` only runs when a human verifies a *planning package*, while `ml record` and
  hand-written review notes commit on a completely different path. The gate was never wired to the
  writes it polices.
- Three regression cases (**CR1–CR3**) in `test-parts-gates.sh`, one of which is the fail-open
  itself: *the leak must still be caught with `git` unavailable*. **39 passed, 0 failed.**

### What three rounds actually cost, and what they say

27 findings, **0 Critical, none in the authorization logic** — the surface the lenses were aimed at
came back clean every round. Every finding landed in the **verification and scaffolding layer**:
Postman cells, shell runners, CI wiring, a gate script, docs. Of the 16 in rounds 1–2, 12 were Low.

The uncomfortable pattern is that rounds 2 and 3 found defects **exclusively in the previous round's
fixes**, and that the defects got structurally worse before they got better — a vacuous test in
round 2, a fail-open gate in round 3. Fixes written quickly at the end of a long session are the
least reviewed code in the change, and they are written with the least context remaining. The
loop-termination rule is not ceremony; it is the only thing that looked at them.

Two mitigations came out of this and are worth more than the fixes themselves: **prove a test by
mutating the code it guards** (done for the advice test and for the clean-room gate — both now have
a demonstrated failing state), and **wire a gate to the write path it polices**, not to whoever
happens to run a verifier.

**Coda, at ship time.** Writing *this section* leaked the path a fourth time: describing the
reviewer's planted leak, I quoted it literally into the note. The widened gate caught it during the
pre-ship package verify — the author of the gate, tripped by the gate, in the file explaining the
gate. Two things follow. The class is not a lapse of attention that more care would fix; the leak
rides in on **prose about the leak**, which is exactly when a human's guard is down. And a gate that
only its author remembers to run would not have caught this either — the CI job is what makes it
hold.
