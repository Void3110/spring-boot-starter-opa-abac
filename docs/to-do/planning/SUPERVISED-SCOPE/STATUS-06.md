---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T6: e2e matrix, demo personas, and the guide

**Status:** ✅ DONE

## What shipped

The whole path proven through the rig on **exact ids and counts**, the non-regression proof for T3, and
the documentation of the second access path.

- **Realm accounts + the UX-only role** (`infra/keycloak/realm-export.json`, **+151 lines, 0 deletions**
  — the only realm change slice A makes). Eight new personas, password == username:
  **`sup-anna`** / **`sup-victor`** (members of no team — the headline subjects), **`sup-noreports`**
  (the claim with zero reports — E10's cell), **`outsider-eve`** (neither — E3), and
  **`pm-bob`** / **`pm-carol`** / **`pm-dave`** / **`pm-erin`** (the reports whose seats propagate).
  Plus the realm role **`unit-supervisor`**, whose description states in the export itself that it
  grants nothing. No existing user, role, client or realm setting was touched.
- **`scripts/postman/supervised-scope-matrix.postman_collection.json`** + **`run-supervised-scope-matrix.sh`**
  — entering **through the gateway**, asserting the actual cut. Two folders, two passes (below).
- **`deploy.sh`**: `CATALOG_USER_SERVICE_SUPERVISED_BASE_URL` is passed through to the catalog pods
  (mirroring how `CATALOG_USER_SERVICE_BASE_URL` already is), defaulting to `http://usermgmt:8080`, so
  E8's second pass is one env var + a pod recreate — no compose-file edit, since that file is generated.
- **The guide** — a new section in `docs/guides/TEAM-BASED-AUTHORIZATION.md` (D1). It covers the two
  access paths, the precedence rule, the CONTROL-capable reach rule, both failure classes — **and T3's
  deferred subsection** (see *Decisions*): the ADR-0031 invariant, *ancestor inheritance requires
  membership provenance; a synthesized role is confined to the types it names*, including why the role's
  missing child keys are **not** sufficient on their own.
- **`infra/README.md` + `scripts/postman/README.md`** (D2): the new matrix, its two-pass shape, the
  personas and the `unit-supervisor` marker, the `eeee…` **fixture-id registry entry**, and a new
  **reserved persona family** entry for `sup-*` / `pm-*`.

## Tests

`./gradlew build` green (all modules, Testcontainers ITs against real Postgres) ·
`opa test infra/opa/policies/` **274/274** with `git diff --stat main -- 'infra/opa/**'` still exactly
T3's four files — **zero policy edits in part 1**, which is what E7 claims · local Sonar
**`CLEAN — 0 open findings`** on the changed files · no library module touched.

### The supervised-scope matrix — 18 requests, 43 assertions, 0 failed

Pass 1 (`Matrix`): **16 requests, 38 assertions**. Pass 2 (`Supervised-edge outage`): **2 requests,
5 assertions**. Run twice end to end (before and after the E7 sweep) — green both times, so the runner
is genuinely idempotent and its `eeee…` fixtures do not collide with any other matrix.

| Cell | What went green |
|---|---|
| **E1** (headline) | `sup-anna`, member of **no** team, sees **exactly 3** catalogs — bob's, carol's, and **dave's via carol** (transitivity) — and **not** the READER-seat catalog (the reach rule), and **not** victor's unit |
| **E2** | `sup-victor` sees exactly 1, a set **disjoint** from anna's |
| **E3** | `outsider-eve` → `200` + wire `count: 0` — not 403, not 500 |
| **E10** | `sup-noreports` (the `unit-supervisor` claim, **zero** reports) → `count: 0`; the marker grants nothing |
| **E5** | `GET` 200 · `PUT` 403 · `PUT` with a tags delta (the assign-tags dispatch) 403 · `DELETE` 403 · `_actions` **present** and `{view:true, update:false, delete:false, assign-tags:false}` |
| **E6** | the category **list**, a category, and a product under the supervised catalog → **403 each** |
| **E9** | the dual-hatted `pm-carol` sees the doubly-reachable row **exactly once**, `count` 2 (not 3), and carrying the **membership** role's affordances (`update:true`) — on the very catalog E5e pinned as `update:false` for anna |
| **E4** (headline) | removing `pm-bob` from anna's reports drops his catalog on the **next** request while carol's branch survives, and a direct `GET` of the withdrawn catalog is **403** — withdrawn, not merely hidden |
| **E8** | with only the supervised edge faulted: anna degrades to her **own memberships** (empty here) with **no 5xx**, while `pm-carol`'s membership page is **unchanged** — the proof the fault is confined to that one edge |

**E5e/E9 together are the sharpest cell in the matrix**: the *same* catalog, two subjects, two different
affordance maps — read-only for the supervisor, writable for the member. That is `supervised := S \ M`
visible from outside the process.

### E7 — the enumerated non-regression run

**Rig flavour actually used: `ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2`** — a strict **superset** of
the `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1` flavour the prompt names (`ENABLE_DIRECTORY=1` force-enables
both). The first sweep was run on the named flavour and `run-team-matrix.sh` **preflight-refused** on it
("the identity directory is OFF … the realm account 'dora' was not returned"): that matrix requires the
`UserDirectory` module to be active, as its own header documents. Re-running the whole enumerated set on
the superset is what makes E7 assert what it claims rather than silently dropping a matrix.

**Ran — 12 of the 15 shipped runners, all PASS:**

| # | Runner | Why it is in scope |
|---|---|---|
| 1 | `run-tests.sh` | the lifecycle CRUD suite through the gateway |
| 2 | `run-matrix.sh` | role-based allow/deny (role resolution) |
| 3 | `run-isolation-matrix.sh` | **B4's own `GET /catalogs` matrix — the direct regression for T5** |
| 4 | `run-team-matrix.sh` | team-based role resolution + the dogfood management path |
| 5 | `run-tag-matrix.sh` | tag-gated roles (role resolution + `required_tags`) |
| 6 | `run-filter-matrix.sh` | partial-eval list filtering |
| 7 | `run-hierarchy-matrix.sh` | ancestor **inheritance** on the single-GET path — **T3's constituency** |
| 8 | `run-hierarchy-list-matrix.sh` | the inheritable-grant **list widening** — the other half of T3's blast radius |
| 9 | `run-pagination-matrix.sh` | the list envelope + pinned counts |
| 10 | `run-permission-categories-matrix.sh` | the ladder + category expansion (role resolution) |
| 11 | `run-resource-resolution-matrix.sh` | id'd decisions resolving the role on the governing root |
| 12 | `run-action-enrichment-matrix.sh` | the `_actions` map on the **list** path (T5 rewrote that path) |

**Skipped — 3, each a mutually exclusive rig flavour, not a convenience:**

- **`run-resilience-matrix.sh`** — needs `ENABLE_RESILIENCE_STUB=1`, which by construction repoints the
  **whole** user-service at the fault stub and so disables the real one every other matrix requires.
  (Its subject matter is nonetheless covered here: E8 fault-injects this slice's own edge, using the
  dedicated property precisely *because* B3's mechanism cannot fault one edge.)
- **`run-agent-tool-matrix.sh`** — needs `ENABLE_MCP=1` plus the MCP image. Its catalog-listing cell is
  a *proxy* of `GET /api/v1/catalogs`, which the isolation matrix and this slice's own matrix assert
  **directly** on this rig.
- **`run-spa-auth-smoke.sh`** — needs `ENABLE_SPA=1`, which puts APISIX in bearer-only auth mode.

**Cells 7 and 8 are the ones that matter for T3.** They are the only matrices that exercise ancestor
inheritance end to end, so they are where a provenance-stamp bug would surface as a member losing child
access. Both green, unchanged.

**One non-reproducible red cell, recorded rather than buried.** On the *first* sweep,
`run-tests.sh` failed at `POST /api/v1/teams` → `400 VALIDATION_FAILED`, cascading 19 assertions. It has
been **green three times since**: standalone immediately after, in the full second sweep, and
deliberately re-run **immediately after a catalog-pod recreate** (the one condition that preceded the
failure) to try to reproduce it. Evidence that it is not a slice regression: `POST /api/v1/teams` is a
**user-service write** path, and this slice adds exactly one user-service write
(`/internal/bootstrap/reporting-edges`) and nothing at all on the public team API; the catalog side is
list/read only. The honest gap is that the response **body was not captured** — newman prints only the
status. That is why this ticket's *own* runner now captures the whole response body on a fixture-creation
failure (see the review below); `run-tests.sh` belongs to another matrix and its expectations are
explicitly not this ticket's to edit.

## Architecture review + refactor

Ran the ★ gate inline after the collection and runner were written, before the rig run. **Two
substantive findings, both fixed, and both were then confirmed by the rig.**

1. **The OPA readiness probe was wrong, and it cost a real run (found by executing, not by reading).**
   The runner restarts OPA (T3's confinement is what E6 asserts, and `--watch` does not reliably
   reload), then polled `GET /health`. OPA answers `/health` as soon as the **server** is listening —
   which is *before* the policy bundle is loaded — and a decision asked in that window is `undefined`,
   which every fail-closed client in this repo reads as **deny**. The first run failed exactly there:
   the fixture Category creation came back 403 and the run aborted. **Fixed** by polling a **real
   decision** (`POST /v1/data/catalog/allow` until the body carries a `result` key), with a hard error
   after 60s rather than proceeding into a misleading 403. This is a *generic* rig gotcha, not one
   specific to this matrix — recorded in Mulch.
2. **A fixture-creation failure surfaced as a Python `KeyError`, not as a diagnosis.** The id extraction
   piped straight into `json_field id`, so any non-201 produced a stack trace with the actual cause —
   the service's `problem+json` — discarded. **Fixed** with a `create_via_gateway` helper that captures
   the whole response and prints it. It immediately paid for itself: the very next run printed
   `currency: must not be null`, exposing that the fixture Product payload used invented field names
   (`priceAmount`/`priceCurrency`) instead of the shipped contract's `priceCents`/`currency` — a
   third finding the first two made visible in one run instead of three.

The rest of the checklist, with what it found:

- **Fail-closed.** E3, E8a and E10 are the floor assertions, and each pins `200` + `count: 0` rather
  than merely "not 200" — a 403 or a 500 would fail them. E8 additionally proves the floor is reached
  through the *degrade*, not through an error escaping to the client.
- **Security — the widening that would matter here** is an e2e cell passing for the wrong reason. Three
  guards were built in deliberately: E1 asserts **exact ids and an exact count** (a widened derivation
  fails on the count, a narrowed one on the ids); E1 carries a **negative** id (the READER-seat catalog)
  so the reach rule is asserted, not assumed; and **E6 would go green for the wrong reason before T3
  landed** — an ancestor-less probe returns false — so it is run against the *runtime* shape, through
  the gateway, with the ancestor chain present. The realm marker's cell (E10) uses a subject who **has**
  the claim, so it tests the claim rather than its absence.
- **Concurrency / idempotency.** The runner **self-resets** before seeding (the matrix is deliberately
  not idempotent — E4 *removes* a reporting edge, so a second run would otherwise start from a
  4-catalog org and E1 would fail on the count) and tears down on green. Proven by running it twice end
  to end, with the whole E7 sweep in between.
- **Wiring.** Every seam this ticket adds has a consumer: the realm role → E10; the new personas → every
  cell; the `deploy.sh` env pass-through → E8's second pass (which fails visibly if the variable is not
  plumbed, since anna would keep seeing her unit); the fixture-id prefix → the registry.
- **Boundary / additivity.** No policy file touched (`opa test` 274/274, the policy diff vs `main` is
  still exactly T3's four files). No library module. No existing collection's expectations edited — the
  two red cells in the first sweep were **investigated**, not adjusted. The realm diff is **+151/-0**.
- **Pattern reuse.** The runner mirrors `run-isolation-matrix.sh` throughout: in-network token minting,
  `token_sub` decoding, bootstrap-API seeding, the ltree-carrying `INSERT INTO catalog`, the
  self-reset + teardown-on-green pair, and `KEEP_FIXTURES=1`. The two-pass fault injection mirrors
  `run-resilience-matrix.sh`'s shape while using this slice's **own** edge.
- **Static-analysis gate:** `CLEAN — 0 open findings` on the changed files (this ticket's diff is shell,
  JSON, YAML and Markdown plus one small Java hardening carried over from the T5 review — see below).

## Integration / e2e

The rig run **is** this ticket's validation, recorded above: the supervised-scope matrix (both passes,
twice) plus the 12 enumerated E7 matrices. Rig recipe actually used, end to end:

```bash
./deploy.sh down                                    # so Keycloak RE-IMPORTS the realm (new personas)
./profile.sh up
ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2          # superset of ENABLE_OIDC=1 ENABLE_USER_SERVICE=1
# both app images rebuilt (the user-service image was 3 weeks old and predates T1-T3)
cd scripts/postman && ./run-supervised-scope-matrix.sh
```

## Decisions

- **T3's deferred guide subsection is discharged here**, exactly as `STATUS-03.md`'s *Decisions*
  requires. The guide's new section states the ADR-0031 invariant in its own subsection ("Contents stay
  closed — and it takes TWO things, not one"), including the funnel that stamps `provenance`, the
  reserved-key strip-on-write/overwrite-on-read pair, that **direct grants are untouched**, and that
  **absence is closed** — alongside D1's two access paths, precedence rule, reach rule and both failure
  classes. Nothing from T3 is now outstanding.
- **`sup-noreports` and `pm-dave`/`pm-erin` are personas the package did not name.** The decomposition
  lists five accounts; E10 ("the claim + **zero** reports sees nothing") is unsatisfiable with them,
  because `sup-anna` and `sup-victor` both have reports by construction — asserting E10 on either would
  require mutating the org mid-run and would collide with E4's liveness cell. `pm-dave` gives E1 its
  **transitive** hop (a report's report) and `pm-erin` gives `sup-victor` a unit of his own so E2's
  disjointness is between two real units rather than one unit and an empty set. All are additive realm
  accounts inside this slice's one permitted realm change.
- **A fifth fixture catalog carries the negative reach case.** `eeee…00f0` exists only so `pm-bob` can
  hold a **`reader`** seat on it — the CONTROL-capable reach rule (invariant 3) is otherwise asserted
  nowhere at the rig level, and it is the rule whose violation silently widens a manager through a
  report's unrelated seat.
- **E8 asserts confinement, not just the degrade.** The package's E8 says "list as anna → only her own
  memberships". For a pure supervisor that floor is the empty page, which alone cannot distinguish "the
  supervised edge degraded" from "the whole user-service edge is down". So the pass carries a **second**
  cell: `pm-carol`'s membership page must be **unchanged** during the fault. That is the assertion that
  makes T4's dedicated base-URL and dedicated breaker observable from outside — without it, sharing
  `resolveCallGuard` would still pass E8.
- **E4 runs last in the collection.** It mutates the reporting relation, and every earlier cell depends
  on the original org. Stated in the collection description so the order is not "tidied" later.
- **The E7 sweep was re-run on `ENABLE_DIRECTORY=1`** rather than recording `run-team-matrix.sh` as
  skipped. It is a strict superset of the named flavour, so nothing about the other eleven changes, and
  a matrix that exercises role resolution is too close to this slice's blast radius to drop on a
  technicality.
- **Rig gotcha hit and worked around (environment, not code).** Docker Desktop's image-pull path was
  wedged on this machine (`DeadlineExceeded` resolving `eclipse-temurin:25-jdk`; even `alpine` hung),
  while the registry was reachable from both the host and inside the VM. The base images were fetched
  with **podman** and side-loaded via `podman save | docker load`; the app images were then built with
  `podman build -v "$HOME/.gradle:/root/.gradle"` (the same committed Dockerfiles, with the host's
  Gradle distribution + dependency cache mounted, which cut each build from ">40 min and stalling" to
  under two minutes) and loaded into Docker. **The rig itself still runs entirely on Docker** — podman
  was used only as a build/transport tool, so the "don't mix runtimes" rule is respected. No repo file
  was changed for this. Recorded in Mulch.
- **No seam deviation to report.** Everything the runner touches was verified against the source before
  being built on: `InternalBootstrapController`'s five payload records (`EnsureUser`, `EnsureTeam`,
  `EnsureCustomRole`, `EnsureMembership`, and T1's `SetReportingEdges` with its **declarative replace**
  semantic), the `catalog` table's columns and the ltree `path` expression, the FK cascades that make a
  catalog delete sufficient teardown, the `CatalogEnrichable` verb registry `{view, update, delete,
  assign-tags}` that I5/E5e assert (`mx-3446c4`: verified against the real endpoints, never assumed),
  the catalog `PUT` delta dispatch that turns a tags change into an `assign-tags` decision, and — after
  it bit — the `ProductRequest` contract (`priceCents`/`currency`).

## Part review (layer 2)

**Scope:** the whole of part 1 — **T4–T6** as one diff (commits `89d7720`, `8f6719b`, and this
ticket's): the `SupervisedScopeClient` HTTP edge, the two-leg partitioned list with the read-only
ceiling and the audit event, and the e2e matrix + personas + guide.

**Downgrade recorded (required — this part carries two headline tickets).** This review is the **2A lens
set applied INLINE, in the part-runner's own context**, not the multi-lens 2B path. That is this
environment's routing, not a shortcut: a part-runner is already a subagent, so nesting a review
sub-agent is the rejected fork and the multi-lens path is unreachable here — and no size or risk of diff
changes it. **T5 is a headline ticket** and carries the slice's fail-**open** edge (the
`supervised := S \ M` set difference); **T6's E1/E4 are the headline proofs**. The automatic
whole-delivery **layer-3 review re-covers both at branch scope** after this part lands, which is also
the only scope where cross-part composition (part 0's role and confinement meeting part 1's list) is
visible.

**Findings — five, all fixed within this part; none reached an earlier part.**

1. *(T5, security — the most consequential)* **A mid-request membership revocation could let the
   supervisor role judge membership rows.** The anchor rule constrains which *id* drives the residual,
   but not that the *role resolved on it* is membership-derived — and the two are read at different
   instants. If the anchor's membership is revoked in between, the user-service's ordered fallthrough
   answers with the synthesized supervisor role, whose **vacuous** tag requirement would then drive the
   residual over every other membership row in scope: the exact fail-open the set difference exists to
   prevent, arriving by a race instead of by arithmetic. No test of the arithmetic can catch it — the
   reduction is provably correct and the bug is still there. **Fixed** by validating the anchor role's
   **provenance stamp** (ADR 0031's, already on the wire) and dropping the membership leg as stale, with
   absence-of-stamp reading as "not supervised" so the check can only ever *drop* a leg. Three
   regression tests added. **Re-tested.**
2. *(T4, correctness)* **The dedicated supervised base-URL defaulted to the environment variable, not
   the property.** `${NEW_ENV:${OLD_ENV:localhost}}` looks equivalent to "defaults to the shared
   base-url" and is not: with the old env var unset, the supervised edge silently falls back to
   `localhost` while the rest of the user-service edge goes elsewhere — a permanently degraded feature
   whose only symptom is a WARN. **Fixed** to reference the property, and **pinned by
   `SupervisedEdgeWiringIT`**, which was written *because* a placeholder is otherwise invisible until
   the rig. **Re-tested.**
3. *(T6, test integrity)* **The OPA readiness probe polled `/health`**, which is true before the policy
   bundle loads — so a decision in that window denies and a fixture creation 403s. It cost a real run.
   **Fixed** to poll a real decision, with a hard failure instead of proceeding. **Re-tested (green).**
4. *(T6, diagnosability)* **A fixture-creation failure surfaced as a `KeyError`**, discarding the
   service's `problem+json`. **Fixed**; it immediately exposed finding 5. **Re-tested.**
5. *(T6, correctness)* **The fixture Product payload used invented field names** (`priceAmount` /
   `priceCurrency` instead of the contract's `priceCents` / `currency`) — a seam asserted from a mental
   model rather than from the OpenAPI spec, which is precisely the class the prompt's
   "verify-a-seam-before-you-build-on-it" rule exists to catch. **Fixed** against the spec.
   **Re-tested.**

**Verification the review added, beyond the ticket gates.** Three checks a lens pass exists to force:

- **The T5 re-measurement, done before coding** (the package index's condition for this part). Four
  measurements against artifacts rather than prose: `AbacQueryService`'s four branches read in source;
  the absence of any two-leg overload confirmed; `data.catalog.filter` under the synthesized role
  measured with `opa eval --partial` **and** against the running rig's Compile API, folding to the
  type-eq tautology → `ALLOW_ALL`; and U42's limitation confirmed present in `00-DESIGN` with no ticket
  claiming otherwise. **Reality agreed with the pinned prose in all four** — the residual risk the index
  named did not materialise, and that is now on the record rather than assumed.
- **The composition IT was made non-vacuous.** With an ALLOW_ALL membership residual the `subtreeSpec`
  arm is redundant and `SupervisedListIT` would pass even if the composition were wrong. Its stub OPA
  therefore returns a **real DNF** for a tag-gated membership role and ALLOW_ALL only for the supervisor
  role — mirroring the measurement above — so a membership `apac` row that the subject *also* supervises
  must stay **excluded**, which only holds because `S \ M` ran. That is the fail-open edge proven over
  real SQL rather than argued.
- **The e2e was built so it cannot pass for the wrong reason** — exact ids *and* exact counts, a
  negative id inside the headline cell (the READER seat), and E6 probed on the runtime ancestor-carrying
  shape rather than the ancestor-less one that once green-lit a live fail-open.

**A conservative choice worth naming for layer 3.** When the membership anchor is stale (finding 1) the
**whole** membership leg is dropped, rather than re-anchoring on the next membership id. That is
strictly fail-closed and self-heals on the next request (the revoked id leaves `governedIds`), but it is
narrower than strictly necessary; re-anchoring would be the richer fix and was deliberately not taken
inside a race-condition hardening.

**Cross-part:** nothing found in this part reaches part 0. Findings 1 and 2 are part-1 code; findings
3–5 are part-1 test/fixture code. Part 0's one forward-facing hand-off — T3's deferred guide subsection
— is **discharged** here (see *Decisions*), which is a completion, not a defect and not an escalation.
No `**ESCALATION (cross-part):**` marker is warranted.

**Part gates at close:** `./gradlew build` green (all modules, Testcontainers ITs against real
Postgres) · `opa test infra/opa/policies/` **274/274** with **zero** policy diff beyond T3's four files
· local Sonar **`CLEAN — 0 open findings`** on the changed files · the supervised-scope matrix green on
both passes, twice · the 12 enumerated E7 matrices green · no library module touched ·
`git status --porcelain` clean with no undeclared artifacts.

## Commit

`feat(supervised-scope): T6 e2e matrix, demo personas, rig plumbing, and the guide`
— on `feature/void3110/supervised-scope`.
