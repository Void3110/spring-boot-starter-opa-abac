---
tags:
  - status/active
  - type/review
  - area/abac
  - area/spring
---

# RESOLVE-COALESCING (Slice 7.3) — Code Review

> **Verdict**: **Approved with fixes** (one Low fixed in-review; zero Critical, zero Medium)
> **Scope**: The full 7.3 autonomous-run branch — request-scoped role/ancestor memos (ADR 0023),
> batch `lookupAll` SPI + wire (ADR 0024), the advice batching pass, the gateway-timeout tail, the
> PERFORMANCE.md re-baseline, and the two latent-defect fixes found mid-run (the deferred
> method-security manager; the `ResilientOpaClient.allowAll` sentinel). 51 files, +4,849/−337.
> · **Branch**: `feature/void3110/resolve-coalescing` vs `main` (12 commits, linear)

## Summary

Review ran the **multi-lens adversarial workflow** (Path 2B): eight failure-mode lenses
(fail-closed/authz, security-audit, persistence/concurrency, core-boundary, rego, API-contract,
conflict/dead-code, infra/e2e) fanned out over the diff, every finding adversarially refuted, then a
completeness critic widened the net (unswept siblings, uncalled seams, untested off-states). 11
agents, ~730k tokens, all structured results verified in the journal. **Seven lenses returned empty
finding sets; one Low survived refutation; the critic added nothing.** The reviewer independently
spot-verified the load-bearing invariants from source (below).

## Critical Issues

None.

## Medium Issues

None.

## Low Issues

| # | Issue | Status |
|---|---|---|
| 1 | `run-action-enrichment-matrix.sh` header contradicted its collection: the E6 line still said the catalog verb set *excludes* `assign-tags` (flipped this branch per ADR 0022/PR #65), the E3 label described a removed ungated-teams cell, E4 read as a newman cell (it is a shell-driven smoke), and the new E7a/E7b cut cells were undocumented. Comment-only, no runtime effect. | **Fixed** (header re-baselined; `bash -n` clean) |

## Fail-closed verification

Every error/empty path traced by the fail-closed lens and re-verified:

- **Memo decorators**: a memoized outage **re-throws verbatim**; only the contractual exceptions are
  memoized (a delegate bug propagates un-memoized); bookkeeping failure → pure pass-through (tested:
  the completed-request case); no request → pass-through. A memoized outage hit inside a batch fails
  the **whole batch** before any delegation.
- **Batch wire**: short/extra/duplicate entry, 200-blank, unparseable, every 4xx, 204,
  breaker-open → whole-batch `RoleResolutionException`; the server validates every target before
  resolving any and stays 5xx-over-partial. Strict completeness is enforced at **two** layers
  (client classification + the memo merge).
- **Advice**: per-row rungs (no verbs / cache miss / ancestor failure) omit the row; a batch outage
  omits the **group** with the response intact; all-false→omit and omit-never-fabricate unchanged.
- **`allowAll` sentinel fix**: retry only on null/short/**all-false** (the delegate's actual
  transport pad); exhausted == plain delegate value (`isSameAs`-pinned); breaker-open synthesis
  unchanged. A mixed block — a real 200 answer — is returned after exactly one call (regression
  tests pin both rows).
- **Deferred manager**: resolution failure at first decision = context misconfiguration → the
  request errors; there is no allow path.

## Security audit

- **Cache safety (the slice's #1 risk)**: the role memo key is a `(userId, resourceType,
  resourceId)` record — cross-subject collision impossible by construction; storage is request
  attributes that die with the request (cross-request isolation unit-tested); the ancestor memo
  deliberately carries no `userId` (lineage is a subject-independent fact). Async/scheduler contexts
  have no request attributes → pass-through.
- **`/internal/effective-roles`**: mounted under the permitted `/internal/**` block; **no APISIX
  route**, and the rig's `internal-blocked` route 404s `/internal/*` at the edge. 7.4's delta review
  re-verifies reachability live (hand-forward).
- **No PII in logs**: batch WARNs carry status/class/counts only (mirrors the single-target path).
- **Injection**: target parts are individually form-encoded; the server parses `<type>:<id>`
  strictly (400 on structural defects) and binds ids as `UUID`.
- The 400-posture deviation ("unknown type" → per-entry `role:null`, not 400) is **narrower**, not
  wider: a 4xx is a permanent outage client-side, so reserving it for structural defects keeps
  mixed-type pages resolvable while an ungoverned type honestly resolves no role.

## Concurrency & idempotency

- Memo state is request-confined; the map is a `ConcurrentHashMap`; a same-key race can at worst
  duplicate a delegate call (both outcomes from the same source — no widening, no lock held across
  a remote call).
- The batch exchange is a **read-only GET on the request thread outside any transaction** — the
  guard's retry unit is the whole exchange and cannot double-execute anything (the construction
  comment at the wrap point restates ADR 0017 §3/§4).
- No decision was moved under weaker protection than it acts under; no locked path touched.

## Wiring & sibling sweep

- Every new seam has non-test callers and non-happy-path tests: the memos (BPP-wired; flag-off,
  no-request, double-wrap, JPA-absent, web-absent states tested), `lookupAll` (advice + wire +
  memo integration; outage/violation/empty tested), the batch endpoint (I1 400s), the k6 mode
  (offline cases + the live baseline).
- The one in-review fix (runner header) has no siblings: this branch edited exactly one collection,
  and the other touched runner (`run-load.sh`) was re-baselined in T1. **Siblings clean.**

## Autonomous-run check

- **Laziness**: not found — every ticket's acceptance has the named tests; the e2e asserts the cut
  (E7a/E7b writer-vs-reader maps), not shape; the disproven P2 half (knee stays at 10 req/s) is
  **recorded, not hidden** — the opposite of declared-done-on-partial-work.
- **Self-preferential bias**: STATUS notes were checked against the diff — the review-gate claims
  correspond to real changes (the deferred manager, the 5 test pins, the re-pins), and STATUS-03
  explicitly records "nothing substantive" where the diff agrees.
- **Goal drift**: the invariants held, re-verified from source by the reviewer directly (not
  delegated): `opa-abac-core` has **zero** Spring/JPA imports; the single-target `lookup()` +
  `exchangeAndClassify` are **byte-identical** (the diff of `HttpRoleDefinitionSupplier` is a pure
  append — no deletion lines); **zero** `.rego` changes (`opa test` 212/212 + 32/32);
  `@FunctionalInterface` intact with the full build as the additive proof; the advice batching is
  unconditional (no flag gate). The two mid-run library fixes were *tightenings* (a decorator that
  never applied now applies; a retry that fired on real answers now fires on the sentinel only).

## What's done right

- The memo replays **all three** tri-state outcomes — the deny-path DoS shape and mixed-snapshot
  pages are both closed, and U6 (the supplier-flip disprover) pins the ADR contract directly.
- Strict completeness is defense-in-depth (contract + client + memo-merge).
- The re-measurement was honest: two bound re-pins argued from lifecycle points, one acceptance
  claim disproven and recorded, and two latent defects fixed with regression pins instead of being
  papered over by test edits.
- The perf evidence is reproducible: every number in PERFORMANCE.md maps to kept artifacts under
  `scripts/load/results/`.

## Test results

- `./gradlew build` + `./gradlew test --rerun-tasks`: **green** (all modules, all Testcontainers ITs).
- `opa test`: **212/212** (rig policies) + **32/32** (usermgmt bundle) — zero Rego changed.
- newman: **14/14 runners green**, each on its documented rig posture (directory rig ×12; demo rig
  for the two Phase-3 matrices; B3 stub rig for resilience; SPA posture for the auth smoke).
- Perf protocol runs: `full` (REPS=3), steady `guarded` @5 rps, `ceiling`, `multi-root`,
  `fault-opa` — all validity-gated green; artifacts kept.
