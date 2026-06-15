---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/security
  - area/spring
---

# Supplier-outage fix-slice — decomposition (Slice B2)

> The ordered work list for [[B2-SUPPLIER-OUTAGE]] (Slice B2 of [[POC-ROADMAP]], route step 1). Five
> tickets, one focused commit each. Design: [[00-DESIGN]]. QA: [[10-QA-TEST-CASES]]. Run via the
> [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]. Pinned by ADR [[0014-supplier-outage-error-distinct|0014]];
> source finding [[PERMISSION-CATEGORIES-REVIEW]] C1/C4.
>
> **Packages.** Library: `dev.dmitriikonovalov.opaabac.{core,security,data}`. Example:
> `dev.dmitriikonovalov.example.catalog.*` + `…example.usermgmt.config`. **Zero Rego; zero schema;
> `opa test` 157/157 unchanged.**

## Critical path

```
T1 ──► T2 ──► T5
 │      │
 ├────► T3 ──┤
 └────► T4 ──┘
```

**T1 is the gate** (the `RoleResolutionException` type + the SPI contract javadoc) — everything else
references it. **T2, T3, T4 are independent of each other** once T1 lands: T2 is the spring-security
consumers (the behavioral fix), T3 the spring-data consumers (hardening), T4 the example implementors
(the strict classification + the consumer wrap). They can land in any order after T1. **T5 (e2e +
headline IT + docs + record) is last** — it proves the closed hole through the rig and across the whole
suite. **T1+T2 are the independently-landable subset**: the library spine of the fix — the exception
type, the contract, and the two gate managers that carry the only widening path — is complete and
correct even before the example implementors throw (no production supplier in the library throws, so
the gate change is dormant-but-correct until T4 wires the classifying supplier).

## Two pinned semantics (so the run never stops to ask)

1. **Outage → uniform deny (403 at a gate), NOT a distinct 503.** ADR 0014 §5: there is **no**
   kill-switch and **no** new error code/wire status. An outage answers exactly the `403 ACCESS_DENIED`
   any denied request answers (the server log is the only operator-visible outage signal — WARN at the
   supplier throw-site, DEBUG at the consumer catches). Any test/e2e cell asserting a 503 or a distinct
   outage status is wrong — assert the 403.
2. **The strict HTTP invariant is the whole classification.** ADR 0014 §4: **only `204` →
   `Optional.empty()` (no-role → fallback); only `200`+valid body → resolved; everything else throws.**
   `200`-blank, all 4xx, all 5xx, timeout, connection-refused, malformed-`200` all **throw**. There is
   no "soft" non-200 that maps to no-role. Treating any of these as no-role is the bug.

---

## T1 — Core: `RoleResolutionException` + the tri-state SPI contract (Spring-free, additive)

**Goal.** Give the framework the neutral signal that distinguishes an *outage* (result unknown → the
caller must fail closed) from an authoritative *no-role* (`Optional.empty()` → the policy may fall back)
— without any Spring in core.

**Deliverables.**
- `opa-abac-core`, package `dev.dmitriikonovalov.opaabac.core`:
  - `RoleResolutionException extends RuntimeException` — `(String message)` + `(String message,
    Throwable cause)` constructors. Javadoc: signals the role **source was unavailable** (an outage) so
    the result is **unknown**; the caller MUST fail closed (deny / no widening), never fall back. This
    is **distinct** from an authoritative no-role (`Optional.empty()`). An in-process, deterministic
    supplier (no remote source) never throws. Family-consistent with `AncestorResolutionException`. The
    wrapped cause is for logs only — never surfaced to a client. *(Thrown in T4 by the classifying
    suppliers; caught in T2/T3/T4 by every consumer.)*
  - `RoleDefinitionSupplier` — **javadoc-only** contract change (no signature change): document the
    **tri-state** return — `Optional.of(def)` = resolved · `Optional.empty()` = **authoritative
    no-role** (a designed signal; the policy may fall back to subject realm roles) · **throws
    `RoleResolutionException`** = outage → the caller MUST fail closed. Note the `@throws`. Keep
    `@FunctionalInterface` (the exception is unchecked — lambdas are unaffected).
  - `NoOpRoleDefinitionSupplier`: javadoc note "an in-process supplier never throws
    `RoleResolutionException`". (`DemoRoleDefinitionSupplier` lives in the example — its note lands in T4.)

**Acceptance.** QA **U1**. `./gradlew :opa-abac-core:test` green and **`./gradlew build` green** (the
change is provably additive — a new exception class + javadoc; nothing recompiles differently, no test
stub widens). Unit: `RoleResolutionException` carries message + cause; is a `RuntimeException`; the
`opaabac.core` import set carries **no Spring/JPA import**.

**What NOT to touch.** `opa-abac-core` stays Spring-free (prove with the import set). **No
`lookup(...)` signature change** — the tri-state is the unchecked-throw convention layered on the
existing `Optional` return, documented only. No consumer change yet (T2–T4). No new error code in the
error-contract vocabulary (an outage is a uniform deny, ADR 0014 §6).

---

## T2 — spring-security: the two gate managers fail closed on outage (the behavioral fix)

**Goal.** The realm-fallback widening hole — the *only* widening path — closes: an outage at the gate
denies explicitly and **never reaches the policy's `not has_role_definition` fallback clause**.

**Deliverables.** Package `dev.dmitriikonovalov.opaabac.security`:
- `OpaPreAuthorizeAuthorizationManager.check(...)` — add an **explicit**
  `catch (RoleResolutionException e)` (placed **before** the existing broad `catch (Exception e)`)
  returning `DENY`, with a one-line comment "B2: role-source outage → deny, never the realm fallback
  (ADR 0014)" and a **DEBUG** log (no PII — class/message only). The `roleDefinitionSupplier.lookup`
  call already sits inside the try; the explicit catch makes the deny **legible and tested** rather than
  incidentally swallowed by the catch-all.
- `OpaAuthorizationManager.check(...)` — the identical explicit `catch (RoleResolutionException e) →
  DENY` before its broad catch, same comment + DEBUG log. (Both managers already wrap the whole body in
  `catch (Exception) → DENY`; this ticket only makes the outage path explicit.)

**Acceptance.** QA **U2–U4**. `./gradlew :opa-abac-spring-security:test` green. The headline unit cells:
the supplier **throws `RoleResolutionException`** → `AuthorizationDecision(false)`, the `OpaClient` is
**never invoked** (no context built → no fallback input ever reaches OPA) (U2/U4); the **sibling** cell
— supplier returns `Optional.empty()` → the manager still builds the no-`role_definition` context and
calls OPA (the designed fallback path is **unbroken**) (U3). Tests follow the existing
`OpaPreAuthorizeAuthorizationManagerTest` / `OpaAuthorizationManagerTest` Mockito patterns
(`ArgumentCaptor` to assert OPA was/wasn't called).

**What NOT to touch.** The resolution path (5.97), the `resource()`/type-level branches, the broad
catch-all (it stays as the backstop for *other* exceptions). No signature change. No behavior change for
the `Optional.empty()` path — only the throw path is newly explicit.

---

## T3 — spring-data: `HierarchicalAuthorizer` catches; `SubtreeSpecResolver` is proven (hardening)

**Goal.** The two data-layer library consumers never let the new exception escape uglily — and the one
that already collapses it correctly is **pinned by a test** so a future refactor can't silently break
it. Neither has a realm fallback, so this is hardening + proof, not a behavior change.

**Deliverables.** Package `dev.dmitriikonovalov.opaabac.data.hierarchy`:
- `HierarchicalAuthorizer.isAllowed(...)` — wrap the `roleDefinitionSupplier.lookup(...)` (the
  governing-root lookup) so a `RoleResolutionException` is caught → **`return false`** (the existing
  fail-closed posture; outage and no-role both deny here — there is no fallback). One-line comment "B2:
  outage → deny (no fallback in this seam)" + DEBUG log.
- `SubtreeSpecResolver.subtreeSpec(...)` — **no code change** required: its existing
  `catch (RuntimeException e) → Optional.empty()` already collapses a `RoleResolutionException` to **no
  widening** (fail-closed). This ticket adds **only a test** proving it, and a one-line javadoc note
  that an outage is covered by that catch (so the behavior is documented, not accidental).

**Acceptance.** QA **U5–U6**. `./gradlew :opa-abac-spring-data:test` green.
`HierarchicalAuthorizerTest`: supplier throws → `isAllowed` is `false`, `OpaClient` not invoked (U5).
`SubtreeSpecResolverTest`: supplier throws → `Optional.empty()` (no widening), `ancestorResolver.subtreeOf`
never called (U6). Both follow the existing test patterns in those files.

**What NOT to touch.** The ancestor-collapse semantics (`AncestorResolutionException` → empty chain is a
**different** failure axis — do not entangle it with the role-outage catch). The `subtreeOf` /
inheritable-gate logic. No new behavior — outage already denied/collapsed; this makes it explicit +
tested.

---

## T4 — example: the strict HTTP classification + the consumer wrap + the conformant showcase

**Goal.** The actual outage source stops swallowing failures and **classifies** (throws on outage); the
example list consumer maps the throw to its fail-closed empty page; the dogfooding user-service supplier
becomes contract-conformant.

**Deliverables.**
- `dev.dmitriikonovalov.example.catalog.config.HttpRoleDefinitionSupplier` — **the strict
  classification** (ADR 0014 §4 / pinned semantic #2). Replace the swallow-everything `catch (Exception)
  → Optional.empty()`:
  - `200` + non-blank body → `Optional.of(parse(body))` (unchanged).
  - `204` → `Optional.empty()` (authoritative no-role → fallback) (unchanged).
  - `200` + blank/empty body → **throw** `RoleResolutionException` (a contract-violating 200).
  - **any** non-`204`/non-`200`-valid status (all 4xx, all 5xx, anything else) → **throw**.
  - `IOException` / `InterruptedException` / timeout / connection-refused / Jackson parse failure →
    **throw** (wrap the cause).
  - **WARN** at the throw site: status code or exception class only — **never** the `userId`, token, or
    body. Restore the interrupt flag on `InterruptedException` before throwing.
  - Replace the class javadoc's "Failure posture — NOT fully fail-closed (tracked: B2)" section with the
    now-true posture: outage → throw → the caller denies; only `204` rides the fallback.
- `dev.dmitriikonovalov.example.catalog.config.CategoryListAuthorizer.readable(...)` — wrap the
  `roleDefinitionSupplier.lookup("catalog", catalogId…)` call so a `RoleResolutionException` →
  **`return Page.empty(pageable)`** (matches its existing unauthenticated/no-role empty-list posture;
  prevents the otherwise-uncaught throw becoming a 500). One-line comment + DEBUG log.
- `dev.dmitriikonovalov.example.usermgmt.config.TeamRoleDefinitionSupplier.lookup(...)` — **minimal
  touch**: wrap the `users.findBySubject` / `effectiveRoles.managementRole` data access so a
  `org.springframework.dao.DataAccessException` → **throw** `RoleResolutionException("team role source
  unavailable", e)`. The authoritative no-role cases (not a team / unparseable id / no user / not a
  member) stay `Optional.empty()`. Javadoc note: the outage path is now legible (outcome unchanged —
  user-mgmt has no realm fallback).
- `dev.dmitriikonovalov.example.catalog.config.DemoRoleDefinitionSupplier` — javadoc-only note: an
  in-process supplier never throws `RoleResolutionException`.

**Acceptance.** QA **U7–U14**. `./gradlew build` green (all modules + example tests). The classification
cells against an in-process `com.sun.net.httpserver.HttpServer` stub (the repo convention — no
WireMock): `200`+body → resolved; `204` → empty; `200`-blank → throws; `4xx` → throws; `5xx` → throws;
timeout → throws; connection-refused → throws; malformed-`200` → throws (U7–U12, extending
`HttpRoleDefinitionSupplierTest`). `CategoryListAuthorizer`: supplier throws → empty page, the query
service not called (U13). `TeamRoleDefinitionSupplier`: repo throws `DataAccessException` →
`RoleResolutionException`; no-member → `Optional.empty()` (U14).

**What NOT to touch.** The 5.97 resolver/cache, `AbacQueryService`, the four `findAuthorized` paths,
pagination, every `@OpaPreAuthorize` annotation — byte-identical. No new error code. The user-mgmt
**policy** path (no Rego). The HTTP client construction (timeouts unchanged — resilience is Slice B3).

---

## T5 — e2e + the headline IT + docs + slice record

**Goal.** Prove the hole is closed through the rig and across the whole suite, document the now-true
posture, and close the slice record.

**Deliverables.**
- **The headline IT (the load-bearing proof of the cut).** In
  `example-catalog-management-service/src/test/java/.../config` — `SupplierOutageGateIT` (extends the
  catalog `AbstractPostgresIT` pattern, **real Postgres via Testcontainers**): a subject carrying realm
  `catalog-editor`, an `@OpaPreAuthorize`-gated id'd write, with a `RoleDefinitionSupplier` test bean
  that **throws `RoleResolutionException`** (simulated outage) → **`403 ACCESS_DENIED` problem+json**,
  the handler never runs (row byte-identical). **Contrast cell:** the same subject with a supplier that
  returns `Optional.empty()` (authoritative no-role) → the realm fallback still grants its designed
  reach (the designed path proven unbroken). (QA **I1/I2**.)
- **e2e (optional cell, sized here — not required for the cut).** A `run-*.sh` "stop the user service"
  matrix cell is a *nice-to-have*; the IT is the load-bearing proof (the cut is deterministic in the
  IT). If included: a cell that brings the rig up, takes the user-service route down (or points the
  catalog at a dead base-url), and asserts a realm-`catalog-editor` member's id'd write answers **403**
  (not the widened fallback). Document in STATUS whether it was added or deferred — **no silent cap**.
- **The whole existing suite green** (every `run-*.sh` matrix + `catalog-e2e` + every module's tests +
  `opa test infra/opa/policies/` **157/157 unchanged**): the fix is additive — prove it. (QA **E1**.)
- **Docs:** verify the **`HttpRoleDefinitionSupplier` class javadoc** (T4) and the
  **`RoleDefinitionSupplier` SPI javadoc** (T1) read true; reconcile [[PERMISSION-MODEL]] (the
  fail-closed-posture line: the one widening-on-failure path is now closed — outage throws, only `204`
  falls back) and [[ABAC-AUTHORIZATION]] (the realm-fallback description: it now decides only for
  authoritative no-role, never an outage). Add a short **mechanism note** to one of those guides — no
  new top-level guide is warranted for a one-contract fix; state which you chose in STATUS.
- **Record:** [[POC-ROADMAP]] — B2 shipped, next 6.7; [[USER-STORIES]] — add a B2 security-posture
  story ("as an operator, a role-source outage cannot widen a caller's grant"), phase-tagged; tick the
  [[B2-SUPPLIER-OUTAGE]] index status table through T5.
- **Mulch:** record the durable insights (the tri-state SPI contract; outage-classify-in-supplier /
  fail-closed-map-per-consumer; the strict 204-only HTTP invariant; the 5-consumer sweep) —
  `git restore --staged .` **before** `ml sync`.
- `git mv docs/to-do/planning/B2-SUPPLIER-OUTAGE docs/to-do/implemented/B2-SUPPLIER-OUTAGE`, flip the
  index frontmatter to `status/done`, add the past-tense **Shipped** banner.

**Acceptance.** QA **I1–I2, E1, D1–D2**. `./gradlew build` green; `opa test` **157/157**; the e2e suite
green end-to-end. Frontmatter valid on every touched note; wikilinks resolve; clean-room scan clean.
**No push.**

**What NOT to touch.** ADR 0014 body (immutable). The Rego policies (zero changes — assert
`opa test` count unchanged). `CLAUDE.md` unless a build/run step genuinely changed.

---

## Cross-cutting acceptance

- `./gradlew build` green throughout; **`opa test infra/opa/policies/` 157/157 unchanged** (zero Rego);
  **Testcontainers real Postgres** (never H2) for the headline IT; the e2e suite green end-to-end.
- **`opa-abac-core` stays Spring-free** (T1's exception carries no Spring import).
- **Fail-closed, one contract, never confused:** an outage **throws** at the supplier; every consumer
  maps the throw to its own fail-closed outcome — gate managers → **deny (403)**;
  `HierarchicalAuthorizer` → **`false`**; `SubtreeSpecResolver` → **no widening**;
  `CategoryListAuthorizer` → **empty page**. An authoritative no-role (`204`) → `Optional.empty()` →
  the realm fallback decides **exactly as before** (the designed path, unbroken). The outage **never
  reaches OPA** (no context built / no compile), so `has_role_definition` and the fallback clause are
  byte-identical.
- **Additive:** one new core exception type + javadoc-only SPI contract; NoOp/Demo and any app lambda
  see **zero** behavior change (they never throw). The only behavior changes are
  `HttpRoleDefinitionSupplier` (classification) + the two gate managers (the fix); the data consumers
  are hardening/test-only; `TeamRoleDefinitionSupplier` is the minimal conformance touch.
- **No kill-switch, no new error code, no Rego, no schema.** An outage is a uniform `403` deny; the
  server log (WARN at the supplier, DEBUG at the consumers, no PII) is the only operator signal.
- Clean-room throughout. One focused commit per ticket, identity `Void3110 <void31102025@gmail.com>`,
  **no push**.

## Related

[[B2-SUPPLIER-OUTAGE]] (index) · [[00-DESIGN]] (mechanism + behavior matrix) ·
[[10-QA-TEST-CASES]] (the cases the acceptances reference) · ADR
[[0014-supplier-outage-error-distinct|0014]] (every pinned fork) · [[PERMISSION-CATEGORIES-REVIEW]]
(C1/C4) · [[POC-ROADMAP]] (route step 1; the new B3 row).
