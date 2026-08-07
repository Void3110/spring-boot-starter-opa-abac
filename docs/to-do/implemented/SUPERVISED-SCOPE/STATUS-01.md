---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T1: user-service: the reporting relation, transitive derivation, and `/internal/supervised-targets`

**Status:** ✅ DONE

## What shipped

The org-relation seam (ADR 0029 §4) in the user-service — **additive only**, no existing resolve path
touched, so every shipped persona's behavior is byte-identical.

- **`domain.ReportingEdge`** (`manager_id`, `report_id`) + **`ReportingEdgeRepository`**
  (`findByManagerIdIn` for one BFS frontier expansion, `findByManagerId` for the declarative replace).
  Not an `AbstractSecuredEntity` — a relation between principals carries no tags, like `User`.
- **Liquibase `0007-create-reporting-edge.yaml`** (registered in `db.changelog-master.yaml`):
  `uq_reporting_edge_pair` on the pair, `idx_reporting_edge_manager` for the per-request walk, and
  FKs to `app_user` on both ends with `ON DELETE CASCADE` — deleting a principal cannot leave a
  stale edge that widens a supervisor's reach.
- **`service.SupervisionService`** — the seam proper:
  - `Set<UUID> transitiveReportsOf(UUID)` — breadth-first, visited-set guarded, **manager-exclusive**,
    never null, never throws. Hops **1–10 inclusive** are derived; an 11th hop is a cap breach.
    Both breaches (cap, cycle) collapse the **whole** set to empty with one WARN.
  - `List<UUID> supervisedTargets(String subject, String resourceType)` — the reports' teams filtered
    to **CONTROL-capable** seats, projected to those teams' governed targets. Distinct, empty on any
    breach, and a membership whose role row does not resolve is **dropped, never defaulted**.
  - `int replaceReportsOf(UUID, List<UUID>)` — the write path, validated before anything is written.
- **`service.TeamRoleCapabilities.isControlCapable(String)`** — the reach predicate **derived from the
  shipped ladder** (`forCode(code).contains(CONTROL)`), not a second enumeration of role codes. Add a
  rung to the ladder and the reach rule follows automatically.
- **`service.InvalidReportingEdgeException`** → `422 REPORTING_EDGE_INVALID`
  (`UserMgmtErrorCode` + `ApiExceptionHandler`).
- **`GET /internal/supervised-targets?subject=&resourceType=`** on the existing
  `InternalResolveController` — signature mirrors the shipped `/internal/governed-targets` exactly:
  **always `200`** with a possibly-empty array, an empty array being the authoritative
  "supervises nothing".
- **`POST /internal/bootstrap/reporting-edges`** on `InternalBootstrapController` — the removal seam
  (see *Decisions*).

## Tests

`./gradlew :example-user-management-service:test` → **229 tests, 0 failures** (was 228 before the
review's extra case). `./gradlew build` green across all modules. `opa test infra/opa/policies/`
**266/266** — unchanged, as T1 touches no policy.

- **`service.SupervisionServiceTest`** (unit, stubbed repositories over a real in-memory edge list, so
  the BFS is genuinely exercised): **U1** (3-level chain, transitive + manager-exclusive), **U2**
  (no edges / null id → empty), **U33** (exactly 10 hops resolves *fully*), **U3** (11 hops collapses
  to empty — not a partial 10-hop set), **U4** ×2 (a cycle through the manager *and* one below it),
  **U5** (cycle-closing edge rejected, nothing saved), **U6** (self-edge), **U7** ×2 (duplicate in one
  post → one row; empty set removes all), **U8** (OWNER/ADMINISTRATOR/SENIOR propagate, MEMBER/READER
  do not), **U9** (shared team contributes once), **U10** (other-type team contributes nothing), plus
  re-convergence-is-not-a-cycle, unknown subject, breach-zeroes-targets, unresolvable-role-drops, and
  the null-element case the review added.
- **`SupervisedTargetsIT`** (Testcontainers, real Postgres — never H2): **I1** asserts the derived set
  **by id** over a seeded org (bob OWNER, carol ADMINISTRATOR, dave SENIOR via carol → transitivity;
  a READER seat and an unrelated catalog both excluded), plus type-scoping, own-membership-is-not-
  supervised, the always-200 empty array, the declarative replace/removal, idempotency, and the
  422 wire contract for self-edge + cycle. **I2** is implicit and load-bearing — the class boots under
  `ddl-auto: validate`, which is the proof the changeset matches the entity — with the re-run half
  pinned by `changesetIsIdempotentOnRerun()`.

## Architecture review + refactor

Ran the ★ gate inline after unit-green, before the ITs. **Three substantive findings, all fixed; no
invented churn.**

1. **Duplicated dedup logic (real).** `InternalBootstrapController` recomputed the written count with
   `Set.copyOf(body.reportIds())`, which both duplicated the service's dedup rule and **threw NPE on a
   null element** — a 500 on a malformed fixture post. `replaceReportsOf` now **returns the written
   count** and the controller reports it. Regression test added (`nullReportIdIsStrippedNotThrown`).
2. **Sonar S3776 (real, fixed in this commit).** `hasCycle` scored cognitive complexity 18 (limit 15);
   split into `hasCycle` / `inDegrees` / `successors`.
3. **Sonar S5778 ×2 (test-lambda shape).** A documented FP class, but trivially removable — hoisted
   the `List.of(…)` argument to a local rather than claim the exemption.

**Static-analysis gate: `CLEAN — 0 open findings` on the changed files.** Two remaining findings are
**documented by-design FP classes**, not re-fixed, and marked false-positive in the local instance with
a comment naming the record: **S2160** on `ReportingEdge` (mx-2b2e8f — JPA entities must inherit
`AbstractAuditableEntity`'s id-only, proxy-safe equals) and **S6809** on
`supervisedTargets → transitiveReportsOf` (mx-51c50f — caller is itself `@Transactional` and the
callee is default `REQUIRED` with identical `readOnly` semantics, so proxied and self-invoked are
byte-identical).

The rest of the checklist, with what it found:

- **Fail-closed.** Every breach lands on the empty collection: null manager id, no edges, unknown
  subject, cap breach, cycle, unresolvable role row. **Deliberately NOT caught: a repository/DB
  failure.** Swallowing it here would return `[]`, which on this endpoint is the *authoritative*
  "supervises nothing" — indistinguishable from a real answer, and it would hide the outage from the
  resilience layer so the breaker never trips. Letting it surface as a 5xx is what makes T4 classify
  it as failure **class 1** (org source errored → the subject degrades to their **own memberships**).
  Both routes are fail-closed; only the un-caught one keeps the two classes distinguishable. This
  also mirrors the shipped sibling `governedTargets`, which does not catch either.
- **Security — the widening that would matter here** is a supervisor reaching a team no report
  actually controls. It cannot happen: every membership passes `isControlCapable`, derived from the
  one shipped ladder, so MEMBER/READER **and every custom role** (which projects to `[READ]`) are
  excluded. The **realm marker is absent from this ticket entirely** — the derivation reads only
  `reporting_edge` + memberships + teams, never a claim. The endpoint returns *derived target ids*
  and never any manager/report identity, so it is strictly less revealing than the shipped
  `/internal/governed-targets`; both sit on the same network-isolated `/internal/**` surface.
- **Concurrency / idempotency.** The pair unique constraint plus the declarative replace make a
  double-write converge to one row (U7 + the IT). The walk is a multi-statement read under
  `@Transactional(readOnly = true)`, i.e. the **same read-snapshot semantics as the shipped
  `governedTargets`**: every returned id is backed by a committed CONTROL-capable seat of a real
  transitive report at the moment it was read — never fabricated, at worst momentarily stale.
- **Wiring.** `/internal/supervised-targets` → named consumer is **T4's `SupervisedScopeClient`**
  (part 1); its non-happy paths are tested here (unknown subject, breach, wrong type).
  `POST /internal/bootstrap/reporting-edges` → consumer is **T6's fixtures**; non-happy path tested
  (422 self-edge, 422 cycle, nothing persisted). The exception → advice mapping is asserted on the
  wire, not just constructed.
- **Boundary / additivity.** No library module touched; `opa-abac-core` untouched and still
  Spring-free; no Rego change; no envelope change. Byte-for-byte unchanged: `EffectiveRoleService`
  (T2/T3 own it), `governedTargets`, `resolveForResource`, every policy file, and the OpenAPI spec.
- **Module-layer separation.** Derivation lives entirely in the user-service; **no set difference
  here** — `supervised := S \ M` is T5's, on the catalog side.
- **Pattern reuse.** The endpoint mirrors `/internal/governed-targets`; the bootstrap endpoint mirrors
  the `ensure*` siblings (differing only in the declared replace semantic); the capability test reuses
  `TeamRoleCapabilities` instead of re-enumerating codes.
- **SOLID.** `SupervisionService` is cohesive around one concept (the reporting relation: derive +
  guard the writes); the graph algorithms are pure static helpers with no repository reach.

## Integration / e2e

ITs only — **no rig, by design** (part 0 stays provable with ITs plus `opa test`). `SupervisedTargetsIT`
runs against real Postgres via Testcontainers. `I2`'s `ddl-auto: validate` boot is clean. No e2e in
this ticket; the rig-level proof is T6's (part 1).

## Decisions

- **The removal seam is a DECLARATIVE bootstrap POST, not a `DELETE`.** `POST
  /internal/bootstrap/reporting-edges` takes `{managerId, reportIds}` and the posted set **replaces**
  that manager's whole edge set, so an empty list removes them. Chosen over adding a delete verb
  because it keeps the fixture surface uniformly "declare the desired state", stays idempotent by
  construction, and gives E4 its liveness proof with one endpoint instead of two. It is
  `/internal`-only fixture plumbing, never a public API.
- **A new 422 error code, and the OpenAPI spec is deliberately NOT extended.** No shipped 422 code
  fits a reporting edge (`ROLE_SUBSET_VIOLATION`, `TAG_DEFINITION_INVALID`, `ROLE_DEFINITION_INVALID`
  are all about roles/tags), so per `mx-fb443b` the closed enum was extended with
  `REPORTING_EDGE_INVALID`. `user-mgmt-api.yaml` is **unchanged**, per T1's "OpenAPI: no change":
  the spec is **public-API-only** and its `errorCode` enum is the union of codes the *documented*
  endpoints can emit. This code is emitted **only** from `/internal/bootstrap/reporting-edges`, which
  is deliberately absent from the spec exactly as the four shipped internal endpoints are — so no
  documented path can produce it and the spec stays truthful. Revisit only if a public endpoint ever
  raises `InvalidReportingEdgeException`.
- **Re-convergence is not a cycle, and the two are distinguished properly.** A visited-set BFS alone
  cannot tell a genuine cycle from a diamond (two managers sharing one report) — it sees "already
  visited" in both. Collapsing on re-convergence would silently empty a legitimate matrix-org
  manager's page. So the walk records the edges it traversed (including those landing back on the
  manager or on an already-visited node) and runs **Kahn's algorithm over the walked subgraph**: a
  diamond peels completely, a cycle does not. Both directions are tested
  (`reconvergentOrgYieldsTheSharedReportOnce`, `cycleBelowTheManagerCollapsesToEmpty`).
- **The depth cap is checked on DISCOVERY of an 11th hop, not on frontier emptiness.** A non-empty
  frontier after hop 10 is not a breach — those hop-10 nodes are legitimately derived and may simply
  have no reports. U33 (exactly 10, resolves fully) and U3 (11, collapses) pin both sides of the
  inclusive boundary, which is where an off-by-one would silently empty a legitimate manager.
- **No seam deviation to report.** Every artifact T1 names was verified against the source before
  building on it: `/internal/governed-targets`' signature on `InternalResolveController`, the four
  `ensure`-shaped bootstrap POSTs (confirmed upsert-only, no delete anywhere — which is *why* the
  removal seam was needed), and the `TeamRoleCapabilities` ladder (`owner`/`administrator`/`senior`
  carry `CONTROL`; `member`/`reader` and every custom role project to `[READ]`).

## Commit

`feat(supervised-scope): T1 reporting relation, transitive derivation, supervised-targets endpoint`
— on `feature/void3110/supervised-scope`.
