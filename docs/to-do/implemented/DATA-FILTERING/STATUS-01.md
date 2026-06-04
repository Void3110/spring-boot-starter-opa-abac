---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
---

# STATUS — T1: Partial-eval client — OpaClient.compile + PartialResult model + Compile-API call (core)

> Filled in at the T1 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] T1 and the
> per-ticket loop in [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].

**Status:** ✅ done

## What shipped

Core (`dev.dmitriikonovalov.opaabac.core`), all Spring-free:

- **`PartialResult`** (record) — `Decision{ALLOW_ALL, DENY_ALL, CONDITIONAL}` + `List<Conjunction>`,
  with `allowAll()` / `denyAll()` (the fail-closed value) / `conditional(clauses)` factories. An
  empty/null clause list in `conditional(...)` collapses to `denyAll()` (no satisfiable disjunct).
- **`Conjunction`** (record) — one AND-group / DNF disjunct (`List<Condition>`); `isEmpty()` = vacuously
  true.
- **`Condition`** (record) — `path` + `Operator{EQ, NEQ, IN, CONTAINS}` + `value`; `isTagPath()` /
  `tagKey()` helpers (a `tags.<k>` path is a JSONB tag; anything else is an intrinsic column).
- **`OpaClient`** widened with two **abstract** methods — `compile(AbacContext) → PartialResult` and
  `allowAll(List<AbacContext>) → List<Boolean>` (body filled in T2). Abstract, not `default`, so a custom
  client can't inherit a fail-open filter. `allow` byte-for-byte unchanged.
- **`HttpOpaClient.compile`** — POST `<baseUrl>/v1/compile` with
  `{query:"data.<path>.filter == true", input:{subject,action,role_definition}, unknowns:["input.resource"]}`,
  resource omitted from `input`. Fails closed to `denyAll()` on non-200 / IOException / timeout / malformed.
- **`CompileResponseParser`** (package-private) — translates the OPA Compile AST → DNF `PartialResult`.
  Closed operator set; the resource-type binding (`eq(type,"category")`) is dropped as a tautology; any
  unsupported term poisons the whole residual → `denyAll()`.
- **Build-breaker fix (same commit):** converted the three test `OpaClient` impls to the widened
  interface — `PermissiveSecurityTestConfig.allowAllOpaClient` (catalog, lambda → anonymous class, returns
  `allowAll()` / all-true), `AbacTestConfig.inProcessTeamOpaClient` (user-service, lambda → anonymous
  class, `denyAll()` / all-false stubs), `OpaAbacAutoConfigurationTest.StubOpaClient` (starter, named class
  → added overrides).

## Tests

`./gradlew :opa-abac-core:test` green. New `HttpOpaClientCompileTest` (in-process `HttpServer` stub, no
WireMock) covers **U1–U9**: unconditional → `ALLOW_ALL`; empty result → `DENY_ALL`; single-condition →
`CONDITIONAL` (type binding dropped); DNF with `IN` → two conjunctions, EQ+IN parsed; intrinsic column
(`id`) → non-tags path; fail-closed on 500 / refused / timeout / malformed; unsupported operator (`gt`)
and non-`input.resource` reference → `DENY_ALL`; request shape pinned (`/v1/compile`,
`unknowns:["input.resource"]`, per-type query path, **no `resource` in input**). The three converted test
stubs compile (`compileTestJava` green for starter + catalog + user-service) — `./gradlew build` no longer
red on the interface widening.

## Architecture review + refactor

- **Additivity / boundary — verified.** `git diff --name-only` on `opa-abac-spring-security/` is **empty**
  (security module untouched); `allow()`'s signature/body unchanged; core carries **no** Spring/JPA import.
- **Fail-closed — verified by grep.** In `HttpOpaClient.compile`, both the non-200 path and the catch
  block return `denyAll()`. In the parser, `allowAll()` emerges at **exactly one** site — an explicit
  empty/tautological conjunction; every other branch (absent result, no queries, unsupported term) returns
  `denyAll()`. No path returns `ALLOW_ALL`/all-true on an error.
- **Pattern reuse.** Mirrors `HttpOpaClient.allow`'s JDK-`HttpClient` + Jackson + WARN-without-token
  fail-closed shape; the operator set stays small and closed.
- **One bug found & fixed during the gate:** the `ExprResult`/`ConjunctionResult` static factories were
  first named `unsupported()` / `tautology()`, which **collided** with the record's auto-generated boolean
  accessors (`unsupported()` etc.) → won't compile. Renamed to `notSupported()` / `asTautology()`.
- No other substantive churn — the design is structurally fail-closed; nothing else to refactor.

## Decisions recorded

- **⚠️ Corrected the ALLOW_ALL ⇄ DENY_ALL boundary (the design doc had it inverted).** An empirical probe
  of OPA 1.10.1's Compile API (`opa eval --partial` + `POST /v1/compile`) established the **verified**
  mapping, which is the **opposite** of what `00-DESIGN.md`'s table stated:
  - `{"result": {}}` (empty result, no `queries` key) → the query is **UNSATISFIABLE** → **`DENY_ALL`**
    (the design doc said this → `ALLOW_ALL` — that would be a **fail-open whole-table leak**).
  - `{"result": {"queries": [[]]}}` (a query whose conjunction is **empty**) → **unconditionally true** →
    **`ALLOW_ALL`**.
  - `{"result": {"queries": [[expr,…]]}}` → residual → **`CONDITIONAL`**.
  The implementation follows the **verified** behavior: an absent/ambiguous compile output **denies**, by
  construction. This is the single most important fail-closed correctness point in the parser. (The plan's
  `00-DESIGN.md` "Three outcomes" table should be reconciled to this in the T7 docs pass.)
- The residual always carries the `input.resource.type == "<type>"` binding (since `input.resource` is the
  declared unknown). We always scope the SQL query to one resource type, so that expression is a tautology
  — the parser **drops** an `EQ` on `resource.type` against the known type, and treats any *other*
  constraint on `type` as unsupported → deny.
- Mulch: recorded the compile-AST→DNF parse + the inverted-boundary correction (see below).

## Commit

`feat(data-filtering): T1 partial-eval client — OpaClient.compile + residual model` — _(SHA at commit)_
