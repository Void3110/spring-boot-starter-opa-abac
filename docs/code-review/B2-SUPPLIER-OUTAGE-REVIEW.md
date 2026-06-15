---
tags:
  - status/active
  - type/review
  - area/abac
  - area/security
  - area/spring
---

# B2 Supplier-outage — Code Review

> **Verdict**: ✅ **Approved — no fixes required.**
> **Scope**: Make a role-source *outage* error-distinct from an authoritative *no-role* at the
> `RoleDefinitionSupplier` SPI, closing the one tracked widening-on-failure path (review C1/C4).
> **Branch**: `feature/void3110/supplier-outage` vs `main` · 6 commits · +1163 / −209 (40 files).

## Summary

A multi-lens adversarial review (8 specialist lenses → adversarial refutation → completeness critic →
synthesis) over the full diff returned **zero confirmed findings and zero findings that survived to
refutation** (`confirmed: []`, `refuted: []`). The lenses covered exactly the right surfaces —
fail-closed-authz, core-boundary/additivity, security-audit, persistence-concurrency, api-contract — and
correctly skipped the inapplicable ones (no `.rego`, no `infra/**`, no CI/build files, no merge commits).
The Criticals were spot-verified by hand against source (below). The slice ships as designed.

## Critical Issues

**None.**

## Medium Issues

**None.**

## Fail-closed verification (every error/empty path lands on deny/empty)

The slice's load-bearing invariant — *no path returns a wider decision on error than on success* — holds
across all five `lookup()` consumers (hand-verified, the keystone of the slice):

| Consumer | Outage (`RoleResolutionException`) → | Verified |
|---|---|---|
| `OpaPreAuthorizeAuthorizationManager` | explicit `catch` → `DENY` **before** any `OpaClient.allow` | ✓ (catch present; U2 asserts `opaClient` never called) |
| `OpaAuthorizationManager` | explicit `catch` → `DENY`; the `.orElse(null)` sits inside the try, so the throw is caught (not swallowed — `orElse` only sees the value path) | ✓ (U4) |
| `HierarchicalAuthorizer` | `catch` around the governing-root lookup → `return false` | ✓ (U5; OPA never called) |
| `SubtreeSpecResolver` | the existing `catch (RuntimeException)` collapses it → `Optional.empty()` (no widening) | ✓ (U6 pins the specific type) |
| `CategoryListAuthorizer` | `catch` → `Page.empty(pageable)` | ✓ (U13; query service never called) |

The outage **never reaches OPA at a gate**, so the realm-fallback clause is never fed an outage input —
the C1/C4 cut, proven live by `SupplierOutageGateIT` (I1 outage → 403, OPA never called; I2 empty →
fallback still grants). `HttpRoleDefinitionSupplier` returns empty **only** on `204` and resolves **only**
on `200`+valid; every other outcome throws (200-blank, all 4xx/5xx, timeout, refused, malformed).

## Security audit

- **No widening-on-failure** — the exact bug B2 closes; verified above.
- **No non-`204` mapped to no-role** — strict classification (only literal `204` → empty).
- **No unswept sibling** — all five consumers addressed; `TeamRoleDefinitionSupplier` maps
  `DataAccessException → throw` (the no-role guards stay `Optional.empty()`).
- **No PII in logs/throws** — every WARN/throw message carries only the HTTP status or the exception
  class; the `outageThrow_carriesNoPii` test asserts neither the `userId` nor the body appears in the
  message or cause. The cause is wrapped for logs only, never surfaced to the client (uniform `403`).
- **No injection / cache-across-subject / authn-default surface introduced** — the change adds an
  exception type + catch sites; no new SpEL/SQL/JSONB/ltree, no cache, no subject defaulting.

## Concurrency & idempotency

No entity / Liquibase / `@Version` / JSONB / lock touched. The outage catch sites deny *before* any
handler or mutation runs, add no lock and cache no outage state; a retried request after an outage simply
re-runs the gate. `CONCURRENCY-AND-LOCKING.md` Rules 1–2 are untouched (confirmed by the
persistence-concurrency lens + by inspection).

## Wiring & sibling sweep

Every new seam has a non-test caller and a non-happy-path test: `RoleResolutionException` is **thrown** by
`HttpRoleDefinitionSupplier` + `TeamRoleDefinitionSupplier` and **caught** by all five consumers, each with
a dedicated outage test (U2/U3/U4/U5/U6/U13/U14 + the I1/I2 IT). The two failure axes stay separate — the
new role-source catch in `HierarchicalAuthorizer` is scoped to the role lookup only and does **not**
entangle the existing `catch (AncestorResolutionException)` (chain-collapse, which degrades to
direct-grant-only, not deny). No fix landed, so no sibling sweep was needed.

## Autonomous-run check

- **Laziness** — none: no consumer left on a bare `.orElse(null)` without outage handling; every test
  asserts the actual cut (deny / no-OPA-call / empty-page), not just shape.
- **Self-preferential bias** — none: the `STATUS-0N.md` "review found nothing substantive" notes match the
  diff (the changes genuinely are verbatim-shape mirrors of the existing exception/catch families).
- **Goal drift** — none: fail-closed held across all five tickets; `opa-abac-core` gained no Spring import
  (grep-verified); the change is additive (`lookup()` signature unchanged, NoOp/Demo never throw, zero
  Rego — `opa test` 157/157).

## What's done right

- The tri-state contract is the *minimal* SPI change — an unchecked exception layered on the existing
  `Optional` return, so `@FunctionalInterface` lambdas are untouched and the change is purely additive.
- Narrow catches (`IOException` / `InterruptedException` / `JacksonException`) in the HTTP supplier, so a
  programming bug is never mislabeled as an outage; the interrupt flag is restored before throwing.
- Classification lives in one place (the supplier); each consumer maps to its own fail-closed outcome —
  no library wrapper that could re-introduce the hole.

## Test results

- `./gradlew build`: **green** (all modules + both example services + every IT, incl. `SupplierOutageGateIT`
  against real Postgres via Testcontainers).
- `opa test infra/opa/policies/`: **PASS 157/157** (zero Rego touched on the whole branch).
- newman e2e (live rig, OIDC + user-service, rebuilt B2 images): **10/10 collections green, 167
  assertions, 0 failures** (additivity through the gateway confirmed).
- Multi-lens adversarial review: **8 lenses, 0 confirmed, 0 refuted.**

## Commits (reviewed)

`cd82740` feat(core) · `5854c1c` feat(spring-security) · `eb64466` feat(spring-data) ·
`023d7e3` feat(example) · `53a6bc6` mulch · `f2a40cd` test(e2e) + docs + slice record.
