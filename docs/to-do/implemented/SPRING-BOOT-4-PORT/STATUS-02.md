---
tags:
  - status/done
  - type/project
  - area/architecture
  - area/spring
---

# STATUS — T2: Security 7 pre-migration: covariant `authorize()` (on 3.5)

**Status:** ✅ DONE

## What shipped

- The three managers' decision bodies moved verbatim from `check(...)` to
  **`authorize(...)` with the covariant `AuthorizationDecision` return**:
  `OpaAuthorizationManager`, `OpaPreAuthorizeAuthorizationManager`, and
  `OpaMethodSecurityConfiguration.DeferredAuthorizationManager` (whose internal delegation also
  moved to `delegate.get().authorize(...)`).
- **One deviation from the ticket's letter, forced by the compiler:** `check()` is still
  *abstract* on Security 6.5 (verified via `javap` on the resolved `spring-security-core-6.5.11`
  jar) — the overrides cannot be deleted pre-bump. Each class instead keeps a one-line
  **`@Deprecated` bridge** `check() → authorize()` with a javadoc naming T4 as its deletion point.
  Observable behavior is identical either way; T4 still carries zero Security-API *work* (three
  mechanical one-line deletions when `check()` vanishes from the interface).
- The 32 test call sites renamed `.check(` → `.authorize(` — verified by `git diff --word-diff`
  that the rename is the **entire** diff in the three test files (S2, assertions byte-identical).
- The migration-anticipation javadoc on `OpaPreAuthorizeAuthorizationManager` updated to document
  the done state (`authorize()` is the entry point, covariant return keeps the narrower type).

## Tests

- `./gradlew :opa-abac-spring-security:test` green; full `./gradlew build` green — 824 cases,
  0 failures, both example services' gate ITs re-executed **unchanged** (S3: the
  `AuthorizationManagerBeforeMethodInterceptor` → `authorize()` dispatch path proven live by
  `ResourceResolutionGateIT`, `TagDecisionGateIT`, `SupplierOutageGateIT` et al.).
- Grep-clean: **zero `.check(` call sites** repo-wide (S1) and zero `[deprecation] check`
  compiler warnings (the `@Deprecated` bridges silence the override warning by design).
- `opa test`: untouched (no rego in this ticket; 228 pinned at T1).

## Architecture review + refactor

Port-gate review: **nothing substantive to refactor.** Fail-closed: every deny path is
byte-identical (bodies moved, not edited — word-diff verified). The named widening for this ticket
("Security 7 dispatch altering when the manager runs"): on 6.5 the interceptor already dispatches
`authorize()`, which previously fell through the interface default to our `check()`; now it hits
our override directly — one indirection removed, same body, same decisions, proven by the unchanged
gate ITs. No new seams (the bridges forward, nothing calls them in production). Module layering
untouched.

## Integration / e2e

Full build (Testcontainers ITs on real Postgres) green. e2e fleet: T7.

## Decisions

- **Bridge-not-delete** (above) — the only compilable reading of "retire `check()` on 6.5"; the
  deletion lands in T4 where Security 7 makes `authorize()` abstract.

## Commit

`refactor(security): move manager bodies to covariant authorize(), deprecated check() bridges (T2)`
