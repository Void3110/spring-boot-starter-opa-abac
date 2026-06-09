---
tags:
  - status/active
  - type/review
  - area/abac
  - area/spring-data
  - area/opa
---

# Slice 5.5-B (hierarchy-aware list filter) — Code Review

> **Verdict**: **Approved with one fix.** No Critical issues; every fail-closed invariant held under
> adversarial review. One Medium (a stale "byte-compatible" Javadoc claim) — **fixed**.
> **Scope**: the post-run `/deep-review` (flow phase ④) of the whole slice (T1–T6).
> **Branch**: `feature/void3110/hierarchy-list-filter` vs `main`.

## Summary

The slice composes the shipped 5.5-A resolver with the shipped Phase-5 partial-eval filter so an ancestor
grant widens a list: the OPA residual stays tag-only and hierarchy is a separate app-built `subtreeSpec`
OR-ed in — `combined = scope.and(tagResidual.or(subtreeSpec)).and(notDenied)`. The review (a focused
sub-agent over the full `main...HEAD` production diff, then a self spot-verify of the load-bearing paths)
confirmed the fail-closed posture across every error/empty path and found **no Critical** issues.
`opa-abac-core` is untouched; the 3-arg `findAuthorized` signature is byte-compatible; the residual model /
operator set / `RoleDefinition` / `filter` / `bulk` rego rules are untouched.

## Critical Issues

None.

## Medium Issues

1. **Stale "byte-compatible / behaves exactly as before" Javadoc on the 3-arg `findAuthorized`** —
   `AbacQueryService`. The 3-arg path now AND-s `notDenied()` (via the 4-arg delegation), so a row carrying
   `abac_deny == true` is excluded from a list — even on the 3-arg path. On `main` the tag-only `filter`
   residual did **not** express the leaf deny, so a denied-but-tag-matching row could appear in a list while
   the single-GET (whose `allow` rule applies deny-overrides) returned 403 for it. This is actually a
   **fail-closed hardening** (it closes a real list↔single-GET fail-*open* discrepancy), but the Javadoc's
   "behaves exactly as before" was inaccurate. **Fixed** — the Javadoc now states the signature is
   byte-compatible *and* calls out the one deliberate row-set change (the deny filter) and why it is a
   hardening. Documentation-only; no behavior change in the fix.

## Fail-closed verification (every error/empty path lands on deny/empty)

| Path | Behavior | Verdict |
|------|----------|---------|
| `subtreeOf` ltree — missing/blank/over-deep root path, SQL error | `alwaysFalse()` (empty widening) | ✅ |
| `subtreeOf` CTE — no `DescendantIdSource`, depth breach, cycle, SQL error, empty set | `alwaysFalse()` (never an unbounded `IN`) | ✅ |
| `SubtreeSpecResolver` — null subject / not-inheritable / no role / no verb / any exception | `Optional.empty()` | ✅ |
| Composition | `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` — widening OR-ed **inside** `scope.and` (no cross-catalog leak), `notDenied` AND-ed **outside** the OR (deny overrides) | ✅ |
| `notDenied()` | `(value IS NULL) OR (value <> 'true')` — absent tag kept, `abac_deny=true` excluded; never silently TRUE for a real deny | ✅ |
| Batch path | per-row `AncestorResolutionException` → empty ancestors (direct-grant-only); only ever removes rows | ✅ |
| Rego list-gate clause | scoped to a list (`not input.resource.id`), AND-s `not denied`, requires both the inheritable-relation declaration AND the role granting the verb on an ancestor type; a stranger (no role definition) cannot pass | ✅ |

## Autonomous-run check (laziness / self-preferential bias / goal drift)

- **Laziness:** none — every ticket's deliverables + acceptance met; tests assert the actual **cut** (row
  sets / allow-vs-deny), not just shape; the live e2e ran green through the gateway.
- **Self-preferential bias:** the STATUS notes are candid — they record the real bugs the ★ gates caught
  (the CTE UUID-binding SQL error; the inheritable-grant-lives-on-the-ancestor-type fixture fix; the
  multi-`@SpringBootConfiguration` test-isolation break; the subtreeOf-reads-the-root's-path point; the
  coarse-list-gate design fork) rather than ritual "review found nothing." This review independently
  re-derived the load-bearing checks rather than trusting the notes.
- **Goal drift:** the load-bearing invariant held across all six tickets — `opa-abac-core` never touched,
  the residual never replaced (only AND/OR-composed), the 3-arg stayed signature-compatible, fail-closed
  never weakened. The one deny-filter behavioral change is a *strengthening*, now documented.

## What's done right

- The `subtreeSpec`-OR-inside-scope / `notDenied`-AND-outside placement is exactly right (the two places the
  composition could leak) and is proven by the mandatory no-leak + deny ITs against real Postgres.
- The strategy (ltree pushdown vs CTE bounded walk) stays behind the SPI; the caller only ever gets a
  `Specification`.
- The coarse list-gate rego clause opens only the gate, never the rows — the SQL cut is unchanged, and a
  dedicated test pins that single-resource decisions are unaffected.

## Test results

- `./gradlew build`: **green** (all modules + both example apps + Testcontainers ITs incl.
  `HierarchyListFilterIT` on real Postgres + the `ddl-auto: validate` boot).
- `opa test infra/opa/policies/`: **77/77** (incl. 5 new list-gate cases).
- e2e `run-hierarchy-list-matrix.sh`: **10/10** assertions green through the gateway (widening · two-subjects
  → different sets · deny-removes · stranger-empty · re-parent flip), pre + post re-parent passes.

## Commits

- (this review) `fix(spring-data): correct the 3-arg findAuthorized Javadoc (deny filter is a hardening)` +
  this note.
