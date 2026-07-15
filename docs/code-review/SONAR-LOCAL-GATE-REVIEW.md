---
tags:
  - status/active
  - type/review
  - area/build
---

# Local Sonar Gate + Baseline Triage — Code Review

> **Verdict**: Approved (zero findings)
> **Scope**: Adopt a local SonarQube pre-push gate, bump it to 26.7.0, and triage the whole baseline
> (2 real fail-closed/test fixes + ~140 mechanical smell fixes + 25 recorded false-positive classes).
> **Branch**: `feature/void3110/sonar-local-gate` vs `main` — 69 files, +846/−198 (9 of 14 commits are
> `.mulch/**`-only sync commits).

## Summary

Reviewed via the multi-lens adversarial workflow (8 failure-mode lenses → adversarial refutation →
completeness critic). **Zero confirmed findings, zero refuted** — no lens surfaced an issue to refute.
The diff is overwhelmingly infra/tooling (`.sonar-local/`, build wiring, docs) plus tree-wide mechanical
Sonar cleanup; the only load-bearing behavior change is a genuine **fail-closed hardening** in
`opa-abac-core`. I independently spot-verified the three highest-risk changes below.

## Critical Issues

None.

## Medium Issues

None.

## Fail-closed verification

The one load-bearing change is `HttpOpaClient.requireSafePath`: the private `SAFE_PATH` regex
`[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*` was replaced by a linear-scan `isSafePath` + `MAX_PATH_LENGTH=512`.

- **Why it's a fix, not a risk:** `java.util.regex` compiles the regex's `(…/…)*` group to a *recursive*
  match, so a long resolver-derived path (thousands of `/`-segments) throws `StackOverflowError`. That
  is an `Error`, not an `Exception`, so it **escaped the `catch (Exception)` fail-closed handlers** in
  `allow`/`compile`/`allowAll` and propagated uncaught — a clean deny became an unhandled failure. The
  linear scan runs in constant stack + O(n), and the 512-char cap bounds n regardless.
- **Grammar equivalence — proven, not asserted.** I ran the old regex and the new scan side-by-side over
  32 hand-picked edge cases (traversal `product/../secret`, empty segments `a//b`, leading/trailing `/`,
  dots, control chars, Unicode, `%2f`) **plus 200,000 random fuzz inputs** over a superset alphabet:
  **0 mismatches**. The accept/reject set is identical; only the SOE escape and the length bound are new.
- Every unsafe/empty/over-length path still throws `IllegalArgumentException`, which the caller's
  `catch (Exception)` turns into a deny. Regression test `HttpOpaClientTest.U9d` exercises a ~40k-char
  path failing closed with no SOE.

`CompileResponseParser.mapOperator`: a negated `internal.member_2` now explicitly `yield null`
("not representable in the closed operator set") — behaviorally identical to the prior nested-ternary
`negated ? null : …`, and null → unsupported → the residual fails closed to deny-all. No widening.

## Security audit

- **No injection surface introduced.** The S2077 dynamic-SQL sites (ltree path maintenance) were *not*
  touched by this branch and remain guarded (`assertSafeIdentifier` regex + closed-switch table names,
  all values bound) — recorded as by-design FPs, not modified.
- **No secret/internal-state leak.** The mechanical `catch (Exception e)` → `catch (… _)` edits only
  renamed genuinely-unused catch variables; every block that logged/inspected `e` was left intact
  (verified per-module by the fix agents and re-confirmed here). No error body or log gained the token.
- **No authn edge weakened.** `JwtClaimsSubjectExtractor`, `SecurityConfig` (both examples), and the
  auth managers received only unused-catch-var / method-ref edits; the STATELESS+JWT posture (S4502
  by-design FP) is unchanged.

## Concurrency & idempotency

Not exercised: the diff touches **no** entity mapping, Liquibase changelog, `@Version`, locking,
`mutate()`, JSONB converter, or mutating-handler logic. The touched service files
(`TagAssignmentService`, `TagDefinitionService`, `EffectiveRoleService`, …) carry only mechanical
`catch (_)` / `!isEmpty()` / method-ref edits with no control-flow change. Fast no-op, as expected.

## Wiring & sibling sweep

- **S6206** `ResourceResolutionSupport` class→record: the compact constructor still
  `requireNonNull`s `resolver` + `cache`, `ancestorChainSupplier` stays nullable, and all three call
  sites (`OpaPreAuthorizeAuthorizationManager` ×2 accessors, `OpaAbacAutoConfiguration` constructor) use
  the byte-compatible constructor + `resolver()`/`ancestorChainSupplier()`/`cache()` accessors. No break.
- **S3038** re-abstraction split: `AbstractSecuredEntity` dropped a *redundant* re-declaration of the
  already-abstract `abacResourceType()` (subclass obligation unchanged — every entity still implements
  it); `AbstractHierarchicalEntity` **kept** its re-abstraction of the *defaulted* `abacParent()` with a
  `@SuppressWarnings("java:S3038")` + rationale, because dropping it would let a hierarchical entity
  silently inherit the flat-resource `Optional.empty()` default and be mis-treated as a root. Both
  contracts are byte-for-byte unchanged for subclasses.
- No new seam was added by this branch, so there is nothing zero-caller to flag.

## Autonomous-run check

N/A — this branch was maintainer-driven (interactive triage), not an autonomous run; no `STATUS-0N.md`
notes are present.

## What's done right

- The fail-closed SOE escape is a *real* find that a single-pass triage had mislabeled a false positive;
  the adversarial-verify pass caught it, and the fix is minimal + grammar-preserving + regression-tested.
- FP disposition is belt-and-suspenders: marked false-positive in the local Sonar DB **and** recorded as
  classes in the `quality-gate-sonar` Mulch domain, so a `down -v` re-bootstrap re-judges from the
  catalog rather than re-flagging.
- The version bump correctly preceded the triage (26.7.0 renumbered S8445→S8924), avoiding wasted work.

## Test results

- `./gradlew build`: **green** (all modules + example apps + OpenAPI codegen + Testcontainers ITs +
  `ddl-auto: validate` boot).
- `sonar-local` (changed files vs main): **CLEAN — 0 open findings**. Full-tree `--all`: 1 finding
  (S6474, the open dependency-verification decision — see `QUALITY-GATE-SONAR-BASELINE` §3a).
- `isSafePath` equivalence harness: **0 mismatches / 200,032 inputs** (old regex vs new scan).
- opa test / newman: not run — no `.rego` and no runtime-path files changed.
