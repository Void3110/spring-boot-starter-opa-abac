---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T6: e2e: the production-tier matrix, the E6 flip, and non-regression

**Status:** ✅ DONE

## What shipped

- **`production-tier-matrix.postman_collection.json` + `run-production-tier-matrix.sh`** — the `ffff…`
  fixture set (three catalogs: **staging**, **production**, **untagged**, each with one category and one
  product, each governed by its own `Tier *` team). **27 requests, 57 assertions**, green through the
  gateway. **Zero realm diff**: `sup-anna` is the supervisor and the existing `editor` account is both her
  single report *and* the owner of the three teams — one persona carrying the reach half and the
  membership half at once.
- **The E6 flip in slice A's matrix** — `supervised-scope-matrix.postman_collection.json`'s E6a/E6b/E6c
  went from "contents closed, each 403" to **200 on exact ids**, with the comment rewritten to say the
  cells were flipped *by* this slice and that the closed-contents proof **moved** to B's matrix rather
  than vanishing. A's runner header carries the same correction. Its non-E6 cells are untouched; the
  matrix now runs **42 assertions** (from 39 — the flip added exact-id checks where there was only a
  status code).
- **Doc deltas** — the `ffff…` row in the fixture-id registry (contiguous with the table), the
  **supervised-scope row updated** (it pinned E6 as "contents closed … each 403"), a runner row for the
  new matrix, a note in the reserved-persona paragraph recording the one sanctioned `sup-anna` sharing
  and the two rules that make it safe, and the **e2e/E6-flip paragraph** appended to T4's *Production
  tier* subsection in `docs/guides/TEAM-BASED-AUTHORIZATION.md` (that paragraph only — T4 owns the rest).

## Tests

`opa test infra/opa/policies/` — **301/301**, unchanged (this ticket edits no policy). `./gradlew build`
— **BUILD SUCCESSFUL** (no Java changed since T5).

| Cell | Requests | Asserts |
|---|---|---|
| **E1** | 4 | the four child reads on the **staging** catalog → 200, with **exact ids and exact counts** on both lists |
| **E7** | 2 | anna's category and product list rows carry **no `_actions` key at all** — asserted per row, with a `rows.length` guard so the loop cannot pass vacuously |
| **E2** | 5 | **E2pre** (added at the ★ gate — see below): anna reads the production **catalog** itself → 200, proving the reach is live; then the four child reads → each **403**, the body asserted to contain **no `deny_reason`** and to carry the ordinary `ACCESS_DENIED` code |
| **E6** | 2 | the catalog's **owner** reads the very contents E2 was denied → 200, `_actions` **present** with `view:true` **and** `update:true` (an honest map, not a degraded one) |
| **E3** | 4 | the four reads on the **untagged** catalog → 200 on exact ids (ADR 0030 §3's default) |
| **E5** | 5 | the owner's **strip** / **re-value** / **assign** of `env` → each **409** with `errorCode: TAG_OPERATOR_MANAGED` **asserted by value**; a follow-up cell proving none of the three moved the tier; and **E5e** (added at the ★ gate): the operator endpoint itself → **404 through the gateway** |
| **E4** | 5 | **liveness, both directions**: the operator flips `staging → production` and anna's **very next** child read is 403 (and the list with it), then flips back and the next read is 200 again |

**E8 — the non-regression set, run and recorded.** Every runner the prompt enumerates was **run**;
**none was skipped**. None of the six preflight-requires `ENABLE_DIRECTORY` (verified by grep: only
`run-team-matrix.sh` requires it, and A's runner merely *forwards* the flag when it recreates pods), so
the flavour-superset escalation did not apply and the whole set ran on
`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`.

| Runner | Result |
|---|---|
| `run-supervised-scope-matrix.sh` | ✅ pass 1 16 req / **42 assertions**, pass 2 (E8 outage) 2 req / 6 assertions — 0 failed |
| `run-tests.sh` | ✅ 11 req / 22 assertions |
| `run-filter-matrix.sh` | ✅ 8 req / 29 assertions |
| `run-hierarchy-list-matrix.sh` | ✅ two passes, 3 req / 9 assertions each |
| `run-isolation-matrix.sh` | ✅ 11 req / 20 assertions |
| `run-action-enrichment-matrix.sh` | ✅ 8 req / 19 assertions |
| `run-production-tier-matrix.sh` | ✅ 27 req / **57 assertions** |
| **+ `run-resource-resolution-matrix.sh`** *(added, not required)* | ✅ 8 req / 12 assertions |
| **+ `run-tag-matrix.sh`** *(added, not required)* | ✅ 9 req / 16 assertions |

The two additions are deliberate: they are the enumerated set's blind spot for *this* policy change. T4
added four `denied` clauses to `category.rego`/`product.rego`, and these two matrices are the closest
neighbours that decide **membership** reads on those exact two types — so they are where a
provenance-scoping mistake would surface as a member-visible regression. Both green, unchanged.

**Not run, with the reason** (all outside the enumerated set): `run-matrix.sh`, `run-team-matrix.sh`,
`run-pagination-matrix.sh`, `run-permission-categories-matrix.sh`, `run-hierarchy-matrix.sh` — membership
paths on the same rig flavour, already represented by the six above plus the two additions;
`run-resilience-matrix.sh` (needs `ENABLE_RESILIENCE_STUB=1`), `run-agent-tool-matrix.sh` (needs
`ENABLE_MCP=1`), `run-spa-auth-smoke.sh` (needs `ENABLE_SPA=1`) — each needs a **different rig flavour**,
and neither the role widening nor the tier denies can reach a population they exercise (no supervised
provenance appears in any of them).

## Architecture review + refactor

Inline ★ review over the ticket's diff, **before** the final matrix run. T6 adds no production code, so
the review's weight falls on the one way an e2e ticket fails: **a cell that goes green for a reason
other than the feature.** Two such cells were found and fixed, and both are in the shipped collection.

- **Fail-closed.** The matrix asserts the floor from both sides in one run: the same request is 200 in
  E1b and 403 in E4b, differing only by an operator write. That in-run contrast is what makes the closed
  cells mean *closed by the tier* rather than *closed for any reason*.
- **Security — the widenings that would matter, and how each is now asserted rather than assumed.**
  1. *A vacuous closure.* **The finding.** Every E2 cell asserted a 403 — which is also exactly what anna
     would get if her supervised reach to that one catalog had silently failed to bootstrap. The four
     denials would then have proved nothing. **Fixed by adding `E2pre`**: anna reads the production
     **catalog itself** → 200 (root metadata stays ungated, ADR 0030 §1), so the role *is* resolved and
     the reach *is* live before the four denials are asserted.
  2. *The operator path reachable from outside* (invariant 6). It was prose in the runner header and
     nowhere in the matrix. **Fixed by adding `E5e`**: the same POST that E4a makes against the service's
     own port is made **through the gateway** and must be **404** — APISIX's positive `internal-blocked`
     route at priority 70, asserted rather than trusted.
  3. *A stale policy bundle* passing the closed cells for the wrong reason — the runner restarts OPA
     itself and then polls a **real decision** (never `/health`, which is green before the bundle loads).
  4. *The `_actions` omission being a global regression rather than the scoped contract* — E6a is E7's
     control: the member's rows on the very catalog anna is denied carry a **present, honest** map.
- **Anti-vacuity, checked cell by cell.** E7's per-row loop is guarded by a `rows.length` assertion (an
  empty page would otherwise satisfy `forEach` silently). E5's cells assert the **error code**, not the
  status, so an unrelated 409 (optimistic lock) cannot pass them. E1/E3 assert exact ids **and** counts —
  a widened result fails the count, a narrowed one the ids.
- **Concurrency / idempotency.** The runner self-resets before seeding (the matrix is deliberately not
  idempotent: E4 mutates a tier) and tears down on green only, keeping fixtures for debugging on red.
  The operator merge-upsert is posted twice across E4a/E4d and converges both ways.
- **Cross-matrix discipline.** The one real hazard in this ticket is the shared persona family, handled
  in *Decisions* below and written into the registry.
- **Static-analysis gate** — not applicable: this ticket touches **no `.java`** (`git status` is two shell
  /JSON artifacts plus three docs). The gate ran clean on the changed files at T5 and nothing since has
  changed a Java file.
- **Refactor applied (two).** The two cells above (`E2pre`, `E5e`). The matrix went from 25 requests / 54
  assertions to **27 / 57**, and both additions close a hole the first draft genuinely had.

## Integration / e2e

This ticket **is** the e2e. All numbers above are from live runs against the rig
(`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`, images rebuilt so T1–T4's code reached
the pods, OPA restarted so T4's clauses were live).

Two rig facts worth carrying forward, both confirmed rather than assumed:

- **The catalog service's published host ports are `28081` / `28082`** (BASE_PORT 28080 + pod index),
  which is where the operator calls go. The gateway is not an option for them **by design** — E5e now
  asserts that.
- **Docker's image build/pull path is unreliable on this machine**, so both images were built with
  `podman build -v "$HOME/.gradle:/root/.gradle"` and side-loaded into Docker
  (`podman save --format docker-archive | docker load`, then re-tagged from `localhost/…`). The rig
  itself stays entirely on Docker. Two builds took ~2 minutes each with the host Gradle cache mounted.

## Decisions

- **Seam deviation — the registry's reserved-persona rule needed an explicit, narrow exception, and got
  one rather than a silent violation.** `scripts/postman/README.md` states that the `sup-*`/`pm-*` family
  belongs to `run-supervised-scope-matrix.sh` and that **no other matrix may add a reporting edge** for
  them. B's cells are *only* meaningful on a `provenance=supervised` decision, and minting a second
  supervisor persona would be a **realm diff** this slice explicitly forbids — so the rule and the slice
  boundary genuinely collide. Resolution: B seeds `sup-anna → {editor}` and the registry paragraph now
  records the exception **plus the two rules that make the sharing safe**, both enforced in the runners
  rather than merely stated: **(1)** each runner deletes every reporting edge it manages *before*
  seeding, so whichever runs second starts from its own org; **(2)** B binds **no `pm-*` account** — its
  teams are owned by `editor` — so nothing it leaves behind can widen `sup-anna`'s page in A's E1
  exact-id cell. Verified empirically: both matrices were run back to back, in both orders, green.
- **`editor` plays both halves on purpose.** He is `sup-anna`'s single report (his `owner` seats are
  CONTROL-capable, so her reach derives) **and** the member whose reads E6/E5 assert. One account, two
  provenances — which is also the sharpest possible statement of the contract: the *same* catalog is
  closed to her and open to him at the same instant.
- **The untagged catalog is a third fixture, not a reuse of A's.** A's `dave` catalog is untagged too, but
  coupling B's default-open cell to A's fixture would make each matrix's teardown able to break the
  other. Three catalogs, one per tier state, all under B's own prefix.
- **E4 is last and flips back.** Every earlier cell depends on the original tagging; the flip-back is also
  the second half of the liveness proof (a one-way latch would pass a flip-to-closed test).
- **Two matrices beyond the enumerated set were run** (`run-resource-resolution-matrix.sh`,
  `run-tag-matrix.sh`) because the enumerated set contains no membership-path matrix that decides
  `category`/`product` reads — precisely the surface T4's four clauses sit on.

## Commit

`feat(production-tier): the e2e tier matrix, the E6 flip, and the non-regression record (T6)` — the
`ffff…` matrix + runner (27 cells), slice A's three flipped E6 cells and its runner header, the registry
row + the supervised-scope row + the reserved-persona exception in `scripts/postman/README.md`, and the
e2e/E6-flip paragraph in `docs/guides/TEAM-BASED-AUTHORIZATION.md`, on
`feature/void3110/production-tier`.

## Part review (layer 2)

**Scope:** the whole of part 1 — **T5 + T6** as one diff (`353fb82..HEAD`, the two feature commits:
one IT with nine cases, one e2e matrix + the flip + the doc deltas) — reviewed at the part boundary
after T6's commit.

**Path taken, and the downgrade this records.** The **2A lens set applied INLINE, in this part-runner's
own context** — no review sub-agent was spawned. This is the deep-review skill's own routing for a
part-runner (its path table: *running inside a spawned subagent → 2A applied inline, any size, any risk;
2B unreachable*). **T6 is a headline ticket** — E4's tier-flip liveness and E5's unstrippability are the
two cells that justify the design — and on risk alone it would take the multi-lens 2B path. It took
inline-2A instead. **Layer 3 (the whole-delivery `/deep-review` after this part) re-covers it**, and
should treat T6's collection and slice A's flipped cells as first-priority scope.

**What part 1 is, and what its review therefore checks.** By design (00-DESIGN §Execution parts) this
part carries **no new code edge**: both fail-open edges — T3's failure-to-absent population and T4's deny
clauses — landed in part 0 and were reviewed there. Part 1 is *proof*. So this review asks of each
proof: **could it be green while the thing it proves is false?**

### What was checked, with the evidence

- **The recorded input shapes (T5).** The three ADR 0032 states are asserted on the **serialized bytes**,
  not only the object: `{}` must appear as `"root_attributes":{}` and absent must be a **missing key**.
  The pair is mutually falsifying — reverting T3's null-preserving copy turns the absent case into `{}`
  and breaks I7, while dropping the `NON_NULL`-not-`NON_EMPTY` choice breaks I5. Neither can regress
  quietly.
- **Both failure-state populations (T5 I7).** Asserted **in the same test**, on the same fixture and the
  same outage: the supervised read 403s while the member's identical read 200s. "The supervised path
  closes, the member proceeds" cannot degrade into "both close" without a red test.
- **The safe intermediate state.** Re-verified as a property of the *sequence*, not just the prose: part
  0 left every supervised child read hitting absent-⇒-deny, and the first thing that changes that is T5's
  proof that the field now reaches OPA populated. Nothing between the two commits widens anything: T5
  adds only a test file, T6 adds only e2e artifacts and docs.
- **E4 tier-flip liveness (T6).** Non-vacuous in both directions and within one run: E1b (200) and E4b
  (403) are the **same request**, separated only by an operator write, and E4e returns it to 200. A
  one-way latch, a cached decision, or a stale bundle each fail at least one of the three.
- **E5 unstrippability (T6).** All three delta moves (strip / re-value / assign) asserted **by error
  code**, plus a follow-up cell proving the rejected writes changed no state, plus the new E5e proving
  the operator path is 404 at the gateway. The claim "nothing its owner can do through the API moves the
  tag" is now three rejections and a routing assertion rather than a sentence.
- **The vacuity sweep — where this review actually earned its keep.** Applied to every closed cell:
  *what else would make this green?* It found the E2 group (four 403s that a broken fixture bootstrap
  would have reproduced exactly) and the missing gateway-404 assertion. Both were fixed **inside T6's
  own boundary**, before the commit, and both are recorded in T6's ★ section — this is the same finding,
  recorded once in each layer's own terms rather than duplicated as new.
- **Cross-matrix blast radius.** The one place part 1 can break something outside itself is the shared
  `sup-*` fixture family. Checked by running both matrices back to back **in both orders** (A then B, B
  then A) — both green, A's E1 exact-id cell included — and the invariant that makes it hold (B binds no
  `pm-*` account; both runners delete the edges they manage before seeding) is written into the registry
  rather than left as a property of the current code.
- **Boundary.** `git diff --name-only 353fb82..HEAD` is one test file, two `scripts/postman/` artifacts,
  one slice-A collection, one slice-A runner header, and two docs. **No Java outside tests, no `.rego`,
  no library module** — `opa test` is 301/301 across the whole part, exactly as invariant (5) requires,
  and the local Sonar gate stayed at its 16 documented by-design FPs with nothing new on T5's file.
- **Autonomous-run lens.** *Laziness*: every cited QA case (I5–I8, E1–E8) is implemented and named. *Self-
  preferential bias*: both ★ gates in this part recorded a real change (a Sonar S125 fix; two added
  cells), and neither claimed "nothing found". *Goal drift*: the seven slice invariants were re-checked
  at the part boundary — contents open by direct grant only, the tier deny provenance-scoped, three
  states distinguishable, nothing tier-related in `filter` (asserted positively in T4, and again in T5's
  I6 residual cell), additive-only, operator-managed end to end (now including the gateway-404 cell),
  zero realm/envelope diff (no realm change at all; E2a asserts no `deny_reason`).

### Findings, and what was done about them

**Two, both mine, both fixed inside T6** — the E2 vacuity gap and the unasserted gateway-404 invariant
(detail above and in T6's ★ section). Re-tested after the fixes: the matrix at **27 requests / 57
assertions**, and the full E8 set plus two extra matrices re-run green.

**No cross-part escalation.** Nothing found reaches T1–T4. The two candidates were weighed explicitly
and are **not** escalations: (i) the `ResilientOpaClient` retry-on-deny that T5's assertions had to
accommodate is **shipped B3 behavior** (ADR 0017), documented in the code it lives in, untouched by this
slice and reached by no part of it — recorded in STATUS-05 and Mulch as a test-authoring fact, not a
defect; (ii) the reserved-persona registry rule that B's matrix had to extend is **documentation this
part owns and updated**, not a decision of an earlier part.

**Re-tested after the review:** `./gradlew build` (all modules) green, `opa test infra/opa/policies/`
**301/301**, the production-tier matrix **27/57** green, the enumerated E8 set green (plus two additions),
and slice A's matrix green at 42 assertions with its flipped E6 cells.
