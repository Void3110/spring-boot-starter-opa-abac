---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/spring
---

# Production tier — Code Review

> **Verdict**: Approved with fixes
> **Scope**: Whole-delivery layer-3 review of the PRODUCTION-TIER slice (T1–T6, two-part
> orchestrated autonomous run) — the operator-managed `env` tier, `root_attributes` enrichment,
> the widened supervisor role, the tier denies, the ITs and the e2e matrix.
> · **Branch**: `feature/void3110/production-tier` vs `main`

## Summary

Path 2B (multi-lens adversarial workflow): 8 failure-mode lenses → adversarial refutation →
completeness critic, 34 agents. **16 findings confirmed** (2 Critical — one defect class, 5 Medium,
9 Low), 8 refuted. All 16 fixed on the branch in this review. The run's own gates (per-ticket ★
reviews, two layer-2 part reviews) had caught three real static-analysis findings and one seam
deviation mid-run; everything below is what those gates missed.

## Critical Issues

**1+2. A team-scoped tag key could shadow the operator-managed global `env` and disarm the tier
guard** (`TagDefinitionService.defineForTeam` + `TagAssignmentService.applicableByKey`).
`defineForTeam` checked uniqueness only within the team (no global-collision check — the analogous
guard exists on the role-definition create path and in `requireTeamKey`'s edit path), the schema's
two partial unique indexes deliberately allow the coexistence, and the catalog side collapsed
colliding definitions with `Collectors.toMap(…, (a, b) -> a)` — first-wins over an **unordered**
SQL result. If the team shadow (`operatorManaged=false` by construction) won the merge, the
operator-managed delta rejection skipped `env` entirely: a team owner could mint a TEAM `env`, then
assign/re-value/strip the tier through the ordinary public `PUT` — the supervised population
unilaterally evading the oversight the slice exists for. Exploitability was order-dependent
(Liquibase-seeded globals usually enumerate first), i.e. the invariant rested on accidental
physical row order.

**Fix (both ends):** `defineForTeam` now rejects a TEAM key colliding with an **operator-managed**
global (ordinary shadowing stays open — the TAG-DICTIONARY design intends it); `applicableByKey`'s
merge is now deterministic and flag-preserving (a colliding operator-managed definition wins
regardless of row order — load-bearing for pre-existing shadows). Tests: two new
`TagDefinitionManagementIT` cases (shadow of `env` → 409, shadow of `region` → 201) and two new
`TagAssignmentOperatorManagedTest` cases (the guard holds in **both** merge orders).

## Medium Issues

| # | Issue | Fix |
|---|---|---|
| 3 | **Tier deny failed OPEN on an array-shaped `env`** — bare scalar `==` in both new clauses; `{"env":["production"]}` fired neither deny (measured by `opa eval`). Latent (the seed pins `env` SINGLE/ENUM) but the reference policy adopters copy, and MULTI is first-class. | Shape-tolerant `"production" in root_env_values` with a root-scoped normalizer mirroring `resource_tag_values`, in both `category.rego` + `product.rego`; array-production (deny) and array-non-production (allow) tests in both test files. `opa test` 301 → 308. |
| 4 | (Same defect class as 1–2, persistence lens) — which colliding definition wins was decided by unordered SQL. | Covered by the Critical fix's merge determinism. |
| 5 | **Operator upsert made the slow, retrying dictionary HTTP call inside its write transaction** (`InternalBootstrapController`), pinning a pooled connection for the whole retry budget and widening the stale-`@Version` window — contradicting the before-never-inside discipline all three public writers follow. | Restructured: existence pre-check + dictionary validate **before** the transaction; the load-merge-flush is a short `TransactionTemplate` block. Behavior (404/422/409 precedence, merge semantics, idempotency) unchanged — `OperatorManagedTagWriteIT` 12/12, E4/E5f/E5g live on the rig. |
| 6 | **`AbacResourceCache`'s published contract still promised "never consulted by decisions / a deny puts nothing"** — false for the governing root since T3's read-through memo; the bean is `@ConditionalOnMissingBean`-replaceable and the key carries no subject. | Contract rewritten: decided leaf never read back; governing root is a decision-independent, decision-read memo; implementations MUST be strictly request-bound. Sibling claims in `AbacQueryService` (finding 16) aligned too. |
| 7 | **Demo SPA offered `env` as an editable picker that can only 409** — its hand-written `TagDefinition` DTO lacked `operatorManaged`. | `operatorManaged?: boolean` added; `TagFields` renders operator-managed keys read-only ("(operator-managed)"), never as editors. Verified by typecheck/build (full UI pass needs the rig's in-network tokens; the SPA image is unchanged on the rig). |
| 8 | **`infra/README.md` still stated supervised contents are closed** — this branch opens them by tier. | Section updated (open-by-tier, both slices' rego in the OPA-restart rationale) + a new *Production tier* section pointing at `run-production-tier-matrix.sh`. |

## Low Issues

- **`HierarchicalAuthorizer` never populates `root_attributes`** → supervised roles get a spurious
  deny through that seam even on non-production roots. Fail-closed direction, no main-tree caller;
  mirroring the enrichment would need a new resolver seam ADR 0032 deliberately rejected.
  **Documented as tier-unaware** (class javadoc + ADR 0032 §Consequences) rather than coded.
- **`category_test.rego` lacked the filter-residual tier pin its sibling carries** → mirror
  `test_tier_never_enters_the_filter_residual` added.
- **Catalog OpenAPI `tags` descriptions still said 422-only** → the operator-managed 409
  `TAG_OPERATOR_MANAGED` case added to all three (create-is-an-assign noted on Product).
- **Committed Mulch record embedded a raw `git status` transcript** (mx-02f310) → replaced with the
  command name; JSONL re-validated.
- **Runner fixture guard defeated by `read <<<"$(…)"`** (subshell `exit 1` swallowed; a failed seed
  ran newman into a wall of 404s) → assignment-then-read with explicit checks, all three seed
  sites. Sibling sweep across every runner: no other instance of the pattern.
- **README/collection overclaimed e2e coverage of the "unproven" tier** → corrected in both places
  (the absent state closes at the module layers: rego absent-clause tests + the enrichment IT's
  outage cases; no rig cell forces an enrichment outage).
- **E5d re-verified only one of the three rejected operator writes** → two new cells (E5f/E5g):
  operator echo-merge reads the staging and untagged maps back directly, proving the rejected
  strip/assign moved nothing (57 → 61 assertions). This also makes STATUS-06's "none of the three
  moved the tier" claim true rather than over-claimed.
- **`AbacQueryService` carried the superseded cache contract in two comments** → aligned with the
  amended contract.

## Fail-closed verification

The load-bearing invariant held everywhere the lenses probed: the two-clause tier shape denies on
absent (`not root_attributes`) and production; `{}` opens; enrichment failure ⇒ field absent ⇒
supervised closes while a member proceeds (I7 asserts both halves under one outage); the memo pins
one snapshot per request; `filter` never consults `denied` (now pinned in **both** test files).
The one fail-open found was the **array-shaped `env`** (finding 3) — fixed shape-tolerantly, both
files, with cardinality-twin tests. Refuted as non-issues (verified before dismissal): `null` on
the wire (NON_NULL prevents it), the allowlist-fallback batch recheck (fails closed), the
partial-eval batch path (root-blind by design, at the coarse gate).

## Security audit

The one genuine hole was the **shadow-`env` class** (Critical, fixed at both ends + both-order
tests). E5e (gateway 404s `/internal/*`) and E5 (delta rejection by value) held under re-probe; the
operator path stays in-network; `operatorManaged` appears in no request schema; the cache-contract
fix closes the cross-subject documentation trap before any adopter builds the wider cache.

## Concurrency & idempotency

The operator merge-upsert converges under retry (echo-merge E5f/E5g prove the read-back); the
delta-rejection rides the loaded-entity flow (no TOCTOU); the in-transaction dictionary call
(finding 5) is hoisted out, restoring the decide-then-short-write discipline; a root-tag change
mid-request still yields one coherent decision via the request memo.

## Wiring & sibling sweep

Every fix swept its mirrors: the rego fix landed in both per-type files (the workflow's own refuter
confirmed the sibling was covered); the herestring fix swept all runners (no other instance); the
collision guard's sibling (role-definition reserved-code guard) already existed — the gap was the
tag path only; the cache-contract fix swept both classes carrying the claim (`AbacResourceCache` +
`AbacQueryService`). New seams added by the fixes all have callers and non-happy-path tests.

## Autonomous-run check

Two-part orchestrated run, collected from disk. No laziness found: every ticket's deliverables
exist and the STATUS notes' claims held under cell-level audit with one exception — STATUS-06's
"follow-up cell proving none of the three moved the tier" over-claimed (E5d covered one of three
catalogs); fixed by adding E5f/E5g so the claim is now true. No self-preferential bias: part 0's
layer-2 review disclosed its own downgrade and a real Low finding (the memo trust boundary — which
this review's finding 6 generalized); part 1's disclosed the deviation records. No goal drift: the
additivity proof held (zero library tests modified through T3), core stays Spring-free, exactly one
policy change (T4) — this review's rego fix extends T4's own clauses with their tests.

## What's done right

The two-clause tier shape with per-clause deletion-mutation guards (the naive-negation trap is
mutation-measured: 6 tests fail); the provenance conjunct's 24-test blast radius; the three-state
serialization asserted on raw bytes (I5–I8); the delta-based rejection with the echo carve-out; the
E4 liveness cells in both directions; the E2pre/E5e cells the part-runner's own ★ gate added; the
non-regression enumeration run in full plus two matrices beyond the enumerated set.

## Test results

- `./gradlew build`: **green** (all modules, Testcontainers ITs, `ddl-auto: validate`)
- sonar-local: 19 findings on changed files — **all four rule classes documented by-design FPs**
  (S5778 assertThatThrownBy test lambdas ×11, S1186 annotation-carrier fixtures ×5, S1168 the
  deliberate tri-state null sentinel ×2, S107 JPA hydration ctor ×1); nothing real introduced
- `opa test infra/opa/policies/`: **308/308** (301 + 7 new: 2×3 array-shape cells + the category
  filter pin)
- newman `run-production-tier-matrix.sh`: **61/61 assertions** (57 + E5f/E5g×2 each) on rebuilt
  images (podman-build + side-load), OPA restarted, real-decision readiness poll
- newman `run-supervised-scope-matrix.sh`: **42/42 + 6/6** (both passes, incl. the E8 outage pass)
- demo SPA: `npm run build` (tsc + vite) clean

## Commits

- (this commit) `fix(production-tier): layer-3 review fixes — shadow-env guard, array-tier deny, tx hoist`
