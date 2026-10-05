---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# ENGINE-ERRORS — decomposition

> T1…T6, in order. Each ticket is one focused commit's worth of work, built collaboratively and
> reviewed at its checkpoint. The design is [[00-DESIGN]]; the contract is
> [[0037-indeterminate-decision-distinct-from-deny|ADR 0037]].

## Critical path

```
T1 ──► T2 ──► T3 ──► T5 ──► T6
       └────► T4 ──────┘
```

The failure travels outward, and so does the work: the core throws and resilience stops retrying answers
(T1 — inseparable, see below), the gates and the base advice translate it (T2), the data layer propagates
it (T3), the starter's fallback renders it for adopters without the base (T4, independent of T3), the
catalog example adopts it and both live matrices prove it (T5), then the docs, the changelog and the proof
gates close it (T6).

**Every ticket leaves `./gradlew build` green.** A test that pins 1.3.0's outage-as-deny behaviour for a
layer moves **in the same commit** as that layer's change — the build-breaker rule for this slice.

**Why T1 carries the resilience and MCP changes.** `ResilientOpaClientTest` and two MCP test classes run a
**real** `HttpOpaClient` against an in-process stub answering 503. The moment the client throws, they need
the new retry classification (a status fault has no `IOException` cause) and the MCP catches — splitting
them off would mean writing an intermediate state only to rewrite it a ticket later (adversarial
validation, 2026-10-04).

**What T1 changes at the HTTP surface on its own** (no test exercises it — they all stub `OpaClient` — but
it is true): the gates' catch-all still turns a thrown failure into 403; a **list over a failing OPA
answers 500** (no handler yet) until T2's advice handler makes it 503.

## T1 — Core throws; resilience retries faults, never decisions; the MCP adapters catch the family

**Goal.** `opa-abac-core` reports "could not decide" as a thrown `DecisionIndeterminateException`, the resilience decorator retries thrown transient faults only, and the MCP example — whose tests run the real client — maps the family to its documented answers.

**Deliverables.**
- Core: new `DecisionIndeterminateException` (abstract, protected constructors) and `PolicyEngineException`
  (nested `Kind`, `kind()`, `httpStatus()`, one factory per kind); `RoleResolutionException` re-parented;
  `HttpOpaClient` throws per ADR 0037 §3 with the input defects moved out of the throwing region; the
  `OpaClient` javadoc contract; `PartialResult.error()` / `fromError()` re-documented as "the client refused
  to ask" (not deprecated — see STATUS-01).
- Resilience: `RetryableClassification` classifies a `PolicyEngineException` by kind only;
  `ResilientOpaClient` drops every result predicate and maps `CallNotPermittedException` to `CIRCUIT_OPEN`
  (or `INTERRUPTED` for the backoff interrupt); its javadoc rewritten.
- MCP: `ToolCallAuthorizer` → `CODE_POLICY_UNAVAILABLE` for a non-role family member; `ToolRosterFilter`
  moves `allowAll` inside its try → `allowing(∅)` + WARN; the agent-tool collection's kill-drill **call**
  cells assert `tool-gate-policy-unavailable` (roster and restore cells unchanged).
- **Build-breakers, same commit:** `HttpOpaClient*Test` failure cells; `ResilientOpaClientTest`
  (real client: `allow_failClosedIdentity`, `compile_failClosedIdentity_isErrorNotDenyAll`,
  `allowAll_failClosedIdentity`, `allow_recoversWithinBudget`) and `ResilientOpaClientDecideTest`;
  `ToolCallGateTest` I10/I11 and `ToolRosterFilterTest` I18 (real client).

**Acceptance.** `./gradlew :opa-abac-core:test :opa-abac-spring-security:test --tests '*resilience*' :example-mcp-server:test` green, covering **U1–U17**, **U30**, **U31**, **U34** and **U35**; `./gradlew build` green; `scripts/checks/check-collection-conformance.py` green.

**What NOT to touch.** `CompileResponseParser` (the blind spot stays, pinned by U9); `Resilience4jCallGuard`
and `CallGuard` (the guard keeps recording every thrown fault — U34 pins the consequence; *superseded by the
review: the OPA breaker counts only retried faults, U34 flipped*); the other two
resilience edges' classification; the resilience properties. `opa-abac-core` stays Spring-free.

## T2 — The gates throw `AuthorizationIndeterminateException`; the base advice answers 503

**Goal.** Both Spring gates translate the family into `AuthorizationIndeterminateException`, every degrade-catch in the method gate lets the family through, and `AbstractProblemAdvice` renders it as 503.

**Deliverables.**
- New `AuthorizationIndeterminateException extends AuthorizationServiceException` in
  `dev.dmitriikonovalov.opaabac.security`.
- `OpaPreAuthorizeAuthorizationManager`: the `RoleResolutionException` catch widened to the family →
  throw; rethrow guards ahead of the `catch (RuntimeException)` in the ancestor walk,
  `parentGovernedByRoleTarget` and `resolveAttributesOf`; gate logging to DEBUG.
- `OpaAuthorizationManager`: the same catch change.
- `ActionEnrichmentAdvice`: the role-batch catch widened to the family; behaviour unchanged (omit).
- `AbstractProblemAdvice`: the 503 `DEPENDENCY_UNAVAILABLE` handler for both types.
- **Build-breakers, same commit:** the outage cells of `OpaPreAuthorizeAuthorizationManagerTest` and
  `OpaAuthorizationManagerTest`, and the catalog's `SupplierOutageGateIT` (403 → 503). Checked during
  validation: `OpaPreAuthorizeRootAttributeEnrichmentTest` has no outage cell, and no user-management test
  pins a gate outage — nothing to move there.

**Acceptance.** `./gradlew :opa-abac-spring-security:test` green, covering **U18–U24**, **I1** and **I2**; `./gradlew :example-catalog-management-service:test --tests '*SupplierOutageGateIT'` green, covering **I3**; `./gradlew build` green.

**What NOT to touch.** The gates' `catch (Exception) → DENY` for everything outside the family (ADR 0037
§6); `StepUpRequiredDecision` and the step-up path; `MemoizingRoleDefinitionSupplier`; the request-level
gate's filter-chain rendering (it stays 403 by default).

## T3 — The data layer propagates the family

**Goal.** `AbacQueryService`, `HierarchicalAuthorizer`, `SubtreeSpecResolver` and the built-in ancestor resolvers propagate "could not decide" instead of answering an empty list, `false`, no widening or a degraded chain.

**Deliverables.**
- `AbacQueryService`: both `findAuthorized` overloads propagate from the kill-switch `allow`, `compile`
  and the allowlist batch; the per-row ancestor catch stays `AncestorResolutionException`-only; the
  `fromError` branches stay for a custom client; the class javadoc's "Never fail-open" bullet rewritten.
- `HierarchicalAuthorizer`: rethrow the family from the role lookup and around `opaClient.allow`;
  `@return` / "never throws" javadoc rewritten.
- `SubtreeSpecResolver`: rethrow the family ahead of `catch (RuntimeException _)`.
- `LtreeAncestorResolver`, `RecursiveCteAncestorResolver`: rethrow the family ahead of each source-SPI
  wrap into `AncestorResolutionException` (four sites).
- **Build-breakers, same commit:** the outage cells of `AbacQueryServiceTest`, `HierarchicalAuthorizerTest`,
  `SubtreeSpecResolverTest`, and any example test that reaches these through a throwing stub.

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green, covering **U25–U29** and **U33**; `./gradlew build` green.

**What NOT to touch.** The ancestor resolvers' own SQL classification (`AncestorResolutionException`,
degrade) — splitting it is backlog. `GovernedScopeResolver` (its fail-to-empty contract is the root-list
blind spot, ADR §3a). The residual→`Specification` translation.

## T4 — The starter's fallback advice

**Goal.** An adopter who does not extend `AbstractProblemAdvice` still gets a 503 for an indeterminate decision, without a duplicate handler when they do.

**Deliverables.**
- New standalone `IndeterminateDecisionProblemAdvice` in `dev.dmitriikonovalov.opaabac.autoconfigure`
  (not a subclass; `@Order(Ordered.HIGHEST_PRECEDENCE)`; exactly the two handlers of T2).
- `OpaAbacAutoConfiguration`: registered for a servlet web app, `@ConditionalOnMissingBean(AbstractProblemAdvice.class)`.
- `OpaAbacAutoConfigurationTest` cells for the three registration states (its existing `webRunner` for the
  servlet cells).

**Acceptance.** `./gradlew :opa-abac-spring-boot-starter:test` green, covering **I4** and **I5**; `./gradlew build` green.

**What NOT to touch.** `EntityNotFoundProblemAdvice` and `PersistenceConflictProblemAdvice`; the existing
auto-configuration conditions; no `SecurityFilterChain` and no `AccessDeniedHandler` bean (the app owns
its chain).

## T5 — The catalog example adopts it, and both live matrices prove it

**Goal.** The catalog service surfaces the family as 503 on its lists, and the rig proves both outage classes through the gateway.

**Deliverables.**
- `CategoryListAuthorizer`, `ProductListAuthorizer`: stop catching the outage into an empty page.
- `CatalogListAuthorizer.resolveRole`: stop catching the outage — a membership outage propagates; an
  authoritative no-role keeps the supervised-only degrade.
- `CatalogProvenanceAdvice`: catches name the family; behaviour unchanged (omit `_provenance`).
- Tests: `CategoryListAuthorizerOutageTest`, `ProductListAuthorizerOutageTest`, `CatalogListAuthorizerTest`
  (the membership-outage blocks flip, the no-role block stays), a `GET /catalogs` outage IT.
- e2e: `resilience-matrix.postman_collection.json` E2 asserts 503 + `DEPENDENCY_UNAVAILABLE`;
  `run-resilience-matrix.sh`'s header narrative updated.
- The rig run — **two configurations** (amended at T5: the resilience stub repoints the catalog's WHOLE
  role source, so the agent-tool and main suites cannot share its rig). Rebuild the three images first
  (`ENABLE_MCP=1 ./deploy.sh build` + the user-management image — `up` reuses a stale one), `./profile.sh up`,
  then: **A** `ENABLE_OIDC=1 ENABLE_RESILIENCE_STUB=1 ./deploy.sh up --pods 2` →
  `scripts/postman/run-resilience-matrix.sh`; **B** `./deploy.sh down`, `./profile.sh up`,
  `ENABLE_MCP=1 ./deploy.sh up --pods 2` → `ENABLE_MCP=1 scripts/postman/run-agent-tool-matrix.sh` and
  `scripts/postman/run-tests.sh`.

**Acceptance.** **U32** and **I6** green (`./gradlew :example-catalog-management-service:test`); **E1–E4** green on the rebuilt rig; `./gradlew build` green.

**What NOT to touch.** The resolve stub (`infra/compose.resilience-stub.yaml`) and its modes; the E1 cell;
any Rego (no policy changes in this slice); `HttpRoleDefinitionSupplier`'s classification (ADR 0014 §4);
`HttpGovernedScopeResolver` and `SupervisedScopeClient` (the root-list blind spot).

## T6 — Docs, changelog, proof gates

**Goal.** The behaviour change is documented where adopters look, and the slice passes its mutation and static-analysis gates.

**Deliverables.**
- New `CHANGELOG.md` (from 1.4.0, *Upgrade notes* per ADR 0037 §Consequences); the README links it.
- Guides: `HTTP-RESILIENCE.md` (the sentinel / `fromError` narrative, roughly §111–150, rewritten);
  `AGENT-TOOL-AUTHORIZATION.md` (the failure table's tool-gate and roster rows, and "Why an empty roster is
  the honest answer" — its premise that the batch cannot report failure is gone, its conclusion stands);
  `ABAC-AUTHORIZATION.md`; `PARTIAL-EVALUATION-FILTERING.md`; `REST-API-DESIGN.md` (the 503 row); the
  request-level gate's `AccessDeniedHandler` type check shown once.
- ADR 0014 and ADR 0017: an amendment note pointing at ADR 0037; ADR 0037's status line → implemented; the
  ADR index row updated.
- `ENGINEERING-BACKLOG.md`: item 13 (the built-in ancestor resolvers' own SQL failures), item 14 (the
  root-list scope-resolver blind spot), item 15 (the user-service's direct "role assignable" policy call).
- `POC-ROADMAP.md`: the slice's line; this package → `docs/to-do/implemented/ENGINE-ERRORS/`.
- `./gradlew mutationTest` and `./.sonar-local/sonar-local.sh` run and their findings resolved.

**Acceptance.** **P1–P3**; `scripts/planning/verify-package.sh` green on the moved package.

**What NOT to touch.** The version (`VERSION_NAME` stays `1.4.0-SNAPSHOT`; the release is a separate step
per `RELEASING.md`, published by the maintainer). The MCP-AUTH-LIBRARY planning docs live on their own
branch and are updated there (they inherit the MCP mappings of T1).

## Cross-cutting acceptance

- `./gradlew build` green after every ticket (all modules + integration tests).
- E2 + E4: both outage classes answer as "could not decide" through the gateway, beside E1's transient
  recovery.
- The fail-closed invariant holds on every error path: nothing that denied in 1.3.0 allows in 1.4.0.
