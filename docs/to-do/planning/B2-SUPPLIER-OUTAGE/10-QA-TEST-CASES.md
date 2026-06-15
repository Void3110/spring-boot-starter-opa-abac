---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/security
  - area/spring
---

# 10 — QA test cases: Supplier-outage fix-slice (Slice B2)

> The concrete cases each [[01-DECOMPOSITION|ticket]]'s *Acceptance* references. **U** = unit (core /
> managers / data consumers / example suppliers — no DB, no rig), **I** = integration (catalog service,
> real-Postgres Testcontainers), **E** = e2e through the gateway (newman), **D** = doc-presence checks.
> This is a **contract-semantics** slice: the cases assert that an *outage* throws and every consumer
> maps it to its own fail-closed outcome, while an *authoritative no-role* (`204`) still rides the
> realm fallback — the designed path, unbroken. **Zero Rego — `opa test` 157/157 unchanged.**

## Conventions

- **Unit (core):** plain JUnit — `RoleResolutionException` shape + the `opaabac.core` import set
  (no Spring/JPA).
- **Unit (managers):** the existing `OpaPreAuthorizeAuthorizationManagerTest` /
  `OpaAuthorizationManagerTest` Mockito patterns — a Mockito `OpaClient` + `RoleDefinitionSupplier`
  (stubbed to **throw** vs return `Optional.empty()`), `ArgumentCaptor` / `verify(...).never()` to
  assert whether `OpaClient.allow` was invoked.
- **Unit (data consumers):** the existing `HierarchicalAuthorizerTest` / `SubtreeSpecResolverTest`
  patterns — Mockito supplier stubbed to throw.
- **Unit (example suppliers):** the existing `HttpRoleDefinitionSupplierTest` pattern — an in-process
  `com.sun.net.httpserver.HttpServer` stub (**no WireMock**) returning crafted status/body, and a
  closed/unbound port for connection-refused, a deliberately slow handler for timeout.
  `TeamRoleDefinitionSupplier` — Mockito repo/service stubbed to throw `DataAccessException`.
- **Integration:** `SupplierOutageGateIT` extends the catalog `AbstractPostgresIT` pattern (**real
  Postgres via Testcontainers**, never H2) with a `RoleDefinitionSupplier` **test bean** that throws
  (the simulated outage) vs returns empty (authoritative no-role).
- **e2e:** the whole existing suite must stay green (additivity proof); the optional outage cell is
  sized in T5 but not required for the cut.
- **The pinned contract (ADR [[0014-supplier-outage-error-distinct|0014]] + the two
  decomposition-pinned semantics):**

  | Boundary / situation | Pinned |
  |---|---|
  | HTTP `200` + valid body | resolved (`Optional.of`) |
  | HTTP `204` | `Optional.empty()` — **authoritative no-role → realm fallback** (designed, KEEP) |
  | HTTP `200` + blank body | **throw** `RoleResolutionException` (contract-violating 200) |
  | any `4xx` / `5xx` / other status | **throw** |
  | timeout / connection-refused / `IOException` / parse failure | **throw** (wrap the cause) |
  | gate managers on a throw | **deny** → `AuthorizationDecision(false)` → `403`; `OpaClient` never called |
  | gate managers on `Optional.empty()` | build the no-`role_definition` context → call OPA → the **realm fallback decides** (unbroken) |
  | `HierarchicalAuthorizer` on a throw | `return false`; `OpaClient` never called |
  | `SubtreeSpecResolver` on a throw | `Optional.empty()` (no widening); `subtreeOf` never called |
  | `CategoryListAuthorizer` on a throw | `Page.empty(pageable)`; the query service never called |
  | `TeamRoleDefinitionSupplier` on `DataAccessException` | **throw**; no-member → `Optional.empty()` |
  | outage externally-visible | uniform `403` (no `503`, no new error code) |
  | logging | WARN at the supplier throw-site (status/class, no PII); DEBUG at consumer catches |
  | Rego / `opa test` | **zero changes; 157/157 unchanged** — the outage denies before any OPA call |

---

## Unit — core (T1)

| # | Case | Expected |
|---|------|----------|
| **U1** | `RoleResolutionException` shape + the boundary | `new RoleResolutionException("m")` and `("m", cause)` carry message + cause; it `instanceof RuntimeException`; the `dev.dmitriikonovalov.opaabac.core` import set carries **no Spring/JPA import**; `./gradlew build` green (purely additive — nothing recompiles differently). |

## Unit — the gate managers (T2)

| # | Case | Expected |
|---|------|----------|
| **U2** | `OpaPreAuthorizeAuthorizationManager`: supplier **throws** `RoleResolutionException` on the id'd lookup | `check` returns `AuthorizationDecision(false)`; **`OpaClient.allow` is never invoked** (`verify(opaClient, never())`) — no context built, so no fallback input ever reaches OPA. |
| **U3** | **Sibling (the designed path unbroken):** same manager, supplier returns `Optional.empty()` | the manager builds the context with **no `role_definition`** and **calls** `OpaClient.allow` once (the realm fallback decides downstream) — the empty path is byte-identical to today. |
| **U4** | `OpaAuthorizationManager`: supplier throws → deny; supplier empty → OPA called | mirror of U2/U3 on the request-level manager. |

## Unit — the data-layer consumers (T3)

| # | Case | Expected |
|---|------|----------|
| **U5** | `HierarchicalAuthorizer.isAllowed`: supplier **throws** | returns `false`; `OpaClient.allow` never invoked. (Sibling: supplier empty → `false` too — no fallback in this seam; unchanged.) |
| **U6** | `SubtreeSpecResolver.subtreeSpec`: supplier **throws** | returns `Optional.empty()` (no widening); `ancestorResolver.subtreeOf` **never called** — proves the existing `catch (RuntimeException)` already collapses the new exception (no code change, only this test). |

## Unit — the example suppliers (T4)

| # | Case (`HttpRoleDefinitionSupplier` vs the `HttpServer` stub) | Expected |
|---|------|----------|
| **U7** | `200` + valid role-definition JSON body | `Optional.of(def)` |
| **U8** | `204` | `Optional.empty()` (authoritative no-role) |
| **U9** | `200` + blank/empty body | **throws** `RoleResolutionException` |
| **U10** | `500` (and a `503`) | **throws** |
| **U11** | `404` (and a `400`) | **throws** — no 4xx maps to no-role |
| **U12** | timeout (slow handler past the request timeout); connection-refused (closed port); malformed `200` body (bad JSON) | each **throws**, wrapping the cause; WARN logged with status/class only — assert **no `userId`/token/body** in the message |
| **U13** | `CategoryListAuthorizer.readable`: the role lookup **throws** | returns `Page.empty(pageable)`; `AbacQueryService.findAuthorized` **never called** |
| **U14** | `TeamRoleDefinitionSupplier.lookup`: repo/service throws `DataAccessException` | **throws** `RoleResolutionException`; a no-member subject (repo returns empty) → `Optional.empty()` (the no-role case unchanged) |

## Integration — catalog service, real Postgres (T5)

| # | Case | Expected |
|---|------|----------|
| **I1** | **The headline cut:** a subject carrying realm `catalog-editor`, an `@OpaPreAuthorize`-gated id'd write, a `RoleDefinitionSupplier` test bean that **throws** (simulated outage) | **`403 ACCESS_DENIED` problem+json**; the handler never ran (the target row byte-identical, no version bump) — the outage no longer rides the realm fallback to a wider grant (the C1/C4 cut). |
| **I2** | **The contrast (designed path unbroken):** same subject + endpoint, a supplier test bean that returns `Optional.empty()` (authoritative no-role) | the realm fallback still grants its designed reach (`catalog-editor` → the write succeeds, or the pre-B2 fallback outcome for that endpoint) — proving B2 narrowed only the outage path, not the fallback. |

## e2e — through the gateway (T5)

| # | Case | Expected |
|---|------|----------|
| **E1** | **Suite-wide coexistence:** every existing `run-*.sh` matrix + `catalog-e2e` + `opa test infra/opa/policies/` | all green; **`opa test` 157/157 unchanged**; row counts and decisions numerically identical — the fix is additive (no production supplier in the suite is under outage, so live behavior is unchanged). *(Optional outage cell — a "stop the user service" matrix — is a nice-to-have sized in T5; if deferred, say so in STATUS — no silent cap.)* |

## Docs (T5)

| # | Case | Expected |
|---|------|----------|
| **D1** | the SPI + supplier javadoc | `RoleDefinitionSupplier` documents the tri-state contract (`@throws RoleResolutionException` = outage → fail closed); `HttpRoleDefinitionSupplier`'s class javadoc states the now-true posture (the old "NOT fully fail-closed (tracked: B2)" section is gone). |
| **D2** | reconciliations + record | [[PERMISSION-MODEL]] and [[ABAC-AUTHORIZATION]] state the closed widening path (outage throws; only `204` falls back); [[POC-ROADMAP]] B2 shipped / next 6.7; [[USER-STORIES]] carries the B2 security-posture story; the index status table ticked T1–T5; folder moved to `implemented/` with the Shipped banner. ADR 0014 body untouched. |

## Fail-closed checklist (must all hold — nothing widens)

- [ ] **An outage throws; it is never `Optional.empty()`.** `HttpRoleDefinitionSupplier` returns empty
      **only** on `204` and `200`+valid (U7–U12); every other path throws.
- [ ] **The throw never reaches OPA at a gate.** Both managers deny without building a context or calling
      `OpaClient` (U2/U4) — the realm fallback clause is never fed an outage input.
- [ ] **The designed no-role path is unbroken.** `Optional.empty()` still builds the no-`role_definition`
      context and calls OPA → the fallback decides (U3); the IT contrast cell proves it live (I2).
- [ ] **Every consumer maps the throw to its own fail-closed outcome.** Gate → deny (U2/U4/I1);
      `HierarchicalAuthorizer` → `false` (U5); `SubtreeSpecResolver` → no widening (U6);
      `CategoryListAuthorizer` → empty page (U13).
- [ ] **No PII leaks on the outage path.** The WARN message carries status/class only — no `userId`,
      token, or body (U12).
- [ ] **Additivity holds.** NoOp/Demo/lambda never throw → zero behavior change; `opa test` 157/157
      unchanged (E1); the only behavior change is the example supplier + the two gate managers.
- [ ] **The two failure axes stay separate.** A role-source `RoleResolutionException` is not confused
      with an `AncestorResolutionException` (the chain-collapse axis) — T3 keeps them distinct.

## Related

- [[01-DECOMPOSITION]] (the tickets these cases gate) · [[00-DESIGN]] (§3 behavior matrix, §6 proof
  obligations) · ADR [[0014-supplier-outage-error-distinct|0014]] (the pinned forks) ·
  [[PERMISSION-CATEGORIES-REVIEW]] (C1/C4 — the source finding).
- The shipped template these mirror: `docs/to-do/implemented/RESOURCE-RESOLUTION/10-QA-TEST-CASES.md`
  (the SPI + fail-closed shape; its **B2 baseline cell** is this slice's source).
