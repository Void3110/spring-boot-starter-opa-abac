---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/security
  - area/spring
---

# STATUS — T2: spring-security — the two gate managers fail closed on outage

**Status:** ✅ DONE

## What shipped

The behavioral fix — the only widening path closes. Both gate managers gain an **explicit**
`catch (RoleResolutionException e) → DENY` placed **before** the existing broad `catch (Exception)`:

- **`OpaPreAuthorizeAuthorizationManager.check(...)`** — explicit catch with the comment "B2:
  role-source outage → deny, never the realm fallback (ADR 0014)" + a **DEBUG** log (class name only,
  no PII). The `.lookup(...).orElse(null)` already sat inside the try; the throw now propagates from
  `lookup` (not the value-path `orElse`) to the explicit catch *before* the context is built.
- **`OpaAuthorizationManager.check(...)`** — the identical explicit catch + comment + DEBUG, the
  request-level mirror.
- Both import `dev.dmitriikonovalov.opaabac.core.RoleResolutionException`.

## Tests

- `OpaPreAuthorizeAuthorizationManagerTest` (QA **U2**, **U3**):
  - **U2** `roleSourceOutage_failClosedDeny_neverCallsOpa` — supplier throws → `isGranted()==false`;
    `verify(opaClient, never()).allow(...)` (no context built → no fallback input reaches OPA).
  - **U3 (sibling)** `authoritativeNoRole_buildsEmptyContext_andCallsOpa` — supplier returns
    `Optional.empty()` → the manager builds a context with `roleDefinition()==null` and **calls** OPA
    once (the designed fallback path, byte-identical to today, proven unbroken).
- `OpaAuthorizationManagerTest` (QA **U4** + sibling):
  - **U4** `roleSourceOutage_failClosedDeny_neverCallsOpa` — throw → deny, OPA never called.
  - **U4 sibling** `authoritativeNoRole_callsOpa` — empty → OPA called.
- **4 new, all PASS**; every pre-existing manager test still green (`opaError_failClosedDeny` proves the
  broad catch still backstops non-outage exceptions). `./gradlew :opa-abac-spring-security:test` green.

## Architecture review + refactor

**Nothing substantive to refactor.** The one load-bearing detail checked hard: **catch ordering** — the
explicit `catch (RoleResolutionException)` must precede `catch (Exception)` or it is unreachable; it is
first in both managers (the compiler would reject the reverse, and the tests confirm the DEBUG-not-WARN
path fires). Checks: **fail-closed** — outage throws → caught → `DENY` *before* any `OpaClient.allow`
(U2/U4 `never()`), so the outage never reaches OPA's realm fallback; empty still calls OPA (U3). **
security** — no outage path swallowed into an empty-role context; DEBUG logs class name only, no
userId/token/body. **concurrency** — the catch denies before any handler/mutation, no lock, no cached
outage state; CONCURRENCY-AND-LOCKING Rules 1–2 untouched. **pattern-reuse** — mirrors the existing
broad-catch→DENY idiom made legible (the "error-DENY vs policy-DENY" lesson, Mulch mx-360fdb). The two
managers are deliberately symmetric; no churn invented.

## Integration / e2e

Deferred to T5 (the headline IT `SupplierOutageGateIT` + the whole-suite green run). T2's unit cells
(throw → deny + OPA-never-called; empty → OPA-called) are the deterministic proof of the gate behavior.

## Decisions

None reopened. Implements ADR 0014 §3 (per-consumer mapping: gate → 403) for the two gate consumers.
The throw path is newly **explicit**; the `Optional.empty()` path is unchanged.

## Commit

`feat(spring-security): gate managers fail closed on role-source outage` — branch
`feature/void3110/supplier-outage`.
