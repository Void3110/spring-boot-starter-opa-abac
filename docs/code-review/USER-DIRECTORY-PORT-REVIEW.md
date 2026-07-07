---
tags:
  - status/active
  - type/review
  - area/security
  - area/api
---

# USER-DIRECTORY-PORT — Code Review

> **Verdict**: Approved with fixes
> **Scope**: Slice 2 of the user-directory work — the `UserDirectory` search SPI, the optional
> `opa-abac-keycloak-directory` module, starter auto-config, the bearer-only `search` sub-path of
> `/api/v1/users`, the `catalog-directory` realm client + `ENABLE_DIRECTORY` rig flag, e2e cells,
> and the SPA picker rewrite. · **Branch**: `feature/void3110/user-directory-port` vs `main`
> (6 feature commits + mulch, 51 files, +1901/−195).

## Summary

Multi-lens adversarial review (Path 2B: 8 lenses → per-finding refutation → completeness critic →
synthesis; 13 agents). **The slice's library, endpoint, and SPA code survived every lens clean — 3
findings confirmed, 0 Critical, 0 refuted — and all three live in the e2e infrastructure**, not the
shipped seam. All three were fixed in this review and re-proven live (three consecutive green matrix
runs, including an adversarial pass).

## Critical Issues

None.

## Medium Issues

| # | Finding (lens) | Fix |
|---|---|---|
| 1 | **`run-team-matrix.sh`'s own prereq never enables the directory** (infra-e2e): the header said `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 …` — following it gives a red run on cells 13/15, since the NoOp answers 200-empty (the no-oracle contract makes "off" invisible at the endpoint). | Header prereq → `ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2`, **plus a deterministic preflight**: the service-account grant and a search for a realm-guaranteed account are the discriminators (no-oracle-safe); the run aborts with the actionable command when the directory is off. |
| 2 | **E-pre's least-privilege half had no committed assertion** (critic): the read-200/write-403 proof was a one-time manual check in T5, while the shipped index claimed "E-pre/E1–E3 through the matrix". | The preflight now **pins E-pre as executable assertions**: mints the `catalog-directory` `client_credentials` token in-network, requires `view-users` read → 200 and user create/update/delete → **403 each**, red run on any violation. QA doc updated with the as-built note. |

## Low Issues

| # | Finding (lens) | Fix |
|---|---|---|
| 3 | **Cell 13a was a cross-collection flake** (infra-e2e): carol — the E1 "never-provisioned" probe — is an isolation-matrix fixture (bootstrapped into the shared `app_user` table and never deleted); any isolation run (or a demo click, which happened during this slice's own browser verify) arms a `count=1` failure. | A **reserved probe account `dora`** added to the realm — credential-less (cannot log in, cannot be token-minted), registered in the fixture registry as "no matrix may bootstrap her" — and the runner's preflight wipes any stray provisioned row (memberships first). Cells 13/13a switched to her. Proven adversarially: provisioned dora deliberately, re-ran, the preflight wiped the row and 13a passed. |

## Fail-closed verification

Every error/empty path on the new seam lands on empty, never wider, verified across lenses and tests:
Keycloak unreachable / 5xx / grant-401 → `[]` + WARN (I2b, never throws); blank `q` → `[]` with zero
Keycloak calls (U2b); module absent / flag off → `NoOp` (I3a, `FilteredClassLoader` state boots clean);
the endpoint is 200-empty on every quiet state (I4b); the clamp is enforced on the request **and**
re-enforced on the response (U2a). No path returns more on error than on success.

## Security audit

- **Disclosure ceiling holds at three layers**: the record's components (reflection assert, U1b), the
  JSON wire (exactly two fields per row, I4a), the e2e row (cell 13's `Object.keys` assert).
- **Least privilege now machine-checked**: read 200 / create 403 / update 403 / delete 403 on every
  matrix run (was: manual once).
- **No secret leakage**: WARNs log `e.getMessage()` only; the E-pre INFO logs realm+client, never the
  secret; the demo secret is rig-scoped.
- **No realm enumeration**: blank-`q` short-circuits before any Keycloak call; the no-oracle empty
  hides outage vs zero-match from callers and the UI alike.

## Concurrency & idempotency

n/a by construction — the port is a pure read (no gated mutation in this slice); the auto-config
builds a stateless singleton over a thread-safe admin client; the SPA's provision-on-select rides the
existing idempotent `ensureUser` (both its create and reuse paths were exercised in the browser trace).

## Wiring & sibling sweep

Every new seam has a named consumer and a tested non-happy path (NoOp empty; every Keycloak error
edge; the off/absent auto-config states; the 200-empty endpoint; the E1/E2/E3 cells). **Sibling
sweep for the fix class (stale runner prereqs/hygiene):** all 13 other `run-*.sh` headers checked —
each one's prereq matches what its cells need (none touch the directory); no other collection asserts
a subject another matrix creates. Siblings clean.

## Autonomous-run check

- **Laziness**: the critic caught one instance — E-pre's denied-write half declared "verified during
  T6" but only ever run manually (STATUS-05 even said "the T6 newman cells will pin these" and T6
  didn't). Fixed here; the run's own gates caught nothing else the lenses could confirm.
- **Self-preferential bias**: STATUS notes match the diff (the T2/T4/T5 review findings recorded there
  were real changes); no ritual-refactor claims found.
- **Goal drift**: none — fail-closed/no-oracle, the disclosure ceiling, core-untouched, and additivity
  held across all six tickets (0 findings on the shipped seam itself).

## What's done right

The clamp lives on the contract (one rule for impl + endpoint echo); the grant-401 cause-chain
classification keeps the distinct-WARN contract honest; the off-state is a first-class tested path;
the e2e asserts the actual cut (directory hit + provisioned miss for the same subject, in one run);
the SPA renders outage and empty identically.

## Test results

- `./gradlew build` — green (all modules incl. the new one, both example apps, codegen,
  Testcontainers ITs, `ddl-auto: validate` boots).
- newman team matrix — **3 consecutive green runs, 21 requests / 24 assertions / 0 failed**, the
  last with dora adversarially provisioned first (the preflight wiped her; 13a passed) — plus the
  E-pre preflight assertions on every run.
- No rego touched (verified by the scope lens); `opa test` n/a.

## Review-fix commits

- `fix(e2e): pin E-pre in the team-matrix preflight and make the E1 probe order-independent`
