---
tags:
  - status/planned
  - type/project
  - area/spring-data
  - area/abac
  - area/opa
---

# Data filtering — decomposition

> The ordered work list for [[DATA-FILTERING]] (Phase 5 of [[POC-ROADMAP]]). Seven tickets, one
> focused commit each. Design: [[00-DESIGN|00-DESIGN.md]]. QA: [[10-QA-TEST-CASES]]. Run via the
> [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].
>
> **Packages.** Library: `dev.dmitriikonovalov.opaabac.{core,data,autoconfigure}`. Example:
> `dev.dmitriikonovalov.example.catalog.*`. **Critical path T1 → T3 → T4 → T5 → T6 → T7; T2 parallel
> with T1; T4's allowlist needs T2.** T1 + T2 + T3 are independently landable (pure unit tests).

---

## T1 — Partial-eval client: `OpaClient.compile` + the residual model (core, Spring-free)

**Goal.** Add OPA Compile-API partial evaluation to the core client, returning a neutral DNF residual.

**Deliverables.**
- `PartialResult` record (`Decision{ALLOW_ALL, DENY_ALL, CONDITIONAL}` + `List<Conjunction> clauses`)
  with `allowAll()` / `denyAll()` factories; `Conjunction(List<Condition>)`;
  `Condition(String path, Operator operator, Object value)` with `Operator{EQ, NEQ, IN, CONTAINS}`.
  All in `opa-abac-core`, no Spring/JPA imports.
- `OpaClient.compile(AbacContext) → PartialResult` (abstract; not a default method — see design).
- `HttpOpaClient.compile`: POST `<baseUrl>/v1/compile` with
  `{query: "data/<resolved-per-type-path>/filter == true", input:{subject,action,role_definition}, unknowns:["input.resource"]}`
  (resource omitted from input). Parse `result.queries`/`support` → DNF: absent/`{}`→`ALLOW_ALL`;
  `queries==[]`→`DENY_ALL`; else→`CONDITIONAL`. **Fail-closed to `denyAll()`** on non-200 / IOException /
  timeout / malformed / an expression the parser can't map. Reuse `PolicyPathResolver`, `OpaClientConfig`,
  the JDK `HttpClient`, Jackson. Log WARN without the token.
- A small internal `CompileResponseParser` translating OPA AST terms (`eq`/`equal`/`internal.member_2`,
  operands referencing `input.resource.tags.*` or a known intrinsic) into `Condition`s; an unknown
  term → signal "unsupported" → `denyAll()`.
- **⚠️ Build-breaker — convert the test-lambda `OpaClient`s.** Adding an abstract method makes `OpaClient`
  **no longer a functional interface**, so every lambda impl stops compiling. Convert these to named or
  anonymous classes implementing all methods (initially `compile` can `return PartialResult.denyAll()` and
  `allowAll` an all-false list until T2/T3 give them real bodies): `StubOpaClient` in
  `opa-abac-spring-boot-starter` `OpaAbacAutoConfigurationTest` (named class — just add the overrides), and
  the two lambda beans `PermissiveSecurityTestConfig.allowAllOpaClient()` (catalog) and
  `AbacTestConfig.inProcessTeamOpaClient()` (user-service). **`./gradlew build` is red until these three are
  fixed** — do them in the same commit as the interface change.

**Acceptance.** `./gradlew :opa-abac-core:test` green **and `./gradlew build` green** (the three test
impls compile against the widened interface). In-process `HttpServer` stub proves: trivially-true
body→`ALLOW_ALL`; empty `queries`→`DENY_ALL`; single-condition + DNF bodies→expected `CONDITIONAL`;
500 / refused / timeout / malformed / unparsable→`DENY_ALL`. One test pins the request shape
(`unknowns:["input.resource"]`, no `resource` in `input`, the per-type query path).

**What NOT to touch.** `allow` (unchanged). No Spring deps. No `Specification` here (that's T3). No
example/infra. No batch *logic* (T2) — but the `allowAll` method **signature** is added here too if needed
so the test impls compile once (otherwise add both methods in T1 and fill `allowAll`'s body in T2).

---

## T2 — Batch decision: `OpaClient.allowAll` (core, Spring-free) — parallel with T1

**Goal.** One OPA round-trip for N contexts, for the post-fetch allowlist finisher. **This is a shared
primitive:** besides the Phase-5 allowlist, it backs Phase-6 action enrichment ([[ACTION-ENRICHMENT]]) —
keep the signature **general** (a public `OpaClient.allowAll(List<AbacContext>) → List<Boolean>`, no
filtering-specific coupling), and the `bulk` rego rule reusable. Building it general here avoids building
batch twice (ADR [[0005-partial-eval-to-jpa-specification|0005]]).

**Deliverables.**
- `OpaClient.allowAll(List<AbacContext>) → List<Boolean>` (abstract; `result[i]` ↔ `contexts[i]`).
- `HttpOpaClient.allowAll`: POST the per-type **bulk** rule (`data/<type>/bulk`) with a list input
  `{"input":{"items":[<ctx>,…]}}` → read `result` as a boolean list of the same length. **Fail-closed:**
  any non-200 / IOException / timeout / malformed / length-mismatch → `List.of(false × N)`. Empty input →
  empty list (no call).

**Acceptance.** `./gradlew :opa-abac-core:test` green. Stub proves: a mixed true/false bulk body maps
positionally; 500 / refused / timeout / malformed / wrong-length → all-false of length N; empty input →
empty list, no HTTP call.

**What NOT to touch.** `allow`/`compile`. No Spring. No example/infra.

---

## T3 — `ResidualSpecificationFactory` — residuals → JPA `Specification` over JSONB (spring-data)

**Goal.** Translate a `PartialResult` into a `Specification<T>` over the `tags` JSONB column + intrinsics.

**Deliverables.**
- `ResidualSpecificationFactory.from(PartialResult) → Specification<T>` in `opa-abac-spring-data`:
  - `ALLOW_ALL` → `Specification.where(null)` (no predicate).
  - `DENY_ALL` → `(r,q,cb) -> cb.disjunction()` (always false).
  - `CONDITIONAL` → `OR( AND(conditions) )` over the clauses.
  - per-`Condition` (Postgres dialect, JPA Criteria `function(...)`, bound literals — no SQL strings):
    `EQ`/`NEQ`/`IN` on `tags.<k>` → `jsonb_extract_path_text(tags,'<k>')` compared / `.in(...)`;
    `CONTAINS` on `tags.<k>` (array tag) → `jsonb_exists(tags->'<k>', '<v>')` (the `?` op);
    a non-`tags` path (e.g. `categoryId`) → `root.get("<field>")` intrinsic comparison.
- A small `JsonPathDialect`-shaped internal helper centralizing the JSONB function names (so a future
  non-Postgres dialect is a seam, not a rewrite — **seam only, not a second dialect**).

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green. Unit: each operator yields the expected
Criteria `function(...)`/predicate (captured via a mock/spy `CriteriaBuilder` or a thin capture);
`ALLOW_ALL`→no predicate; `DENY_ALL`→disjunction. **Testcontainers IT:** seed real Category rows with
differing JSONB tags, run the generated `Specification`, assert the exact surviving row set for `EQ`,
`IN`, `CONTAINS`, and a DNF (OR) residual.

**What NOT to touch.** core (`PartialResult` is consumed read-only); example; the single-decision path.
No second SQL dialect.

---

## T4 — `AbacQueryService` seam + optional post-fetch allowlist (spring-data)

**Goal.** Tie "build context → compile → specification → query (→ optional batch finish)" into one seam.

**Deliverables.**
- `AbacQueryService.findAuthorized(JpaSpecificationExecutor<T> repo, Specification<T> scope,
  AbacContext queryContext) → List<T>`:
  1. `compile(queryContext)` → residual; `ResidualSpecificationFactory.from(...)` → `authzSpec`.
  2. `repo.findAll(scope.and(authzSpec))` — SQL does the cut.
  3. **If** the residual was flagged "not-fully-SQL" **and** `allowlistFallback` is on: build a per-row
     `AbacContext` for each survivor (resource = the loaded entity, via `AbacDataObject`) and run
     `allowAll(...)`; drop the `false` rows.
- Carry a `partialEval.enabled` short-circuit: when off, apply `scope` only + one coarse `allow` check
  (pre-Phase-5 behavior) — a true kill-switch, **never fail-open**.

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green. Unit with a mock repo + stub client:
`ALLOW_ALL`→`scope` only; `DENY_ALL`→empty list, no fetch beyond an empty spec; `CONDITIONAL`→`scope.and`
applied; allowlist path invoked only when flagged and toggled, and drops `false` rows; `enabled=false`→
coarse path. IT: end-to-end over real Postgres returns the right rows for two different subjects.

**What NOT to touch.** core; example; the `@OpaPreAuthorize` interceptor.

---

## T5 — Starter wiring (beans, `partialEval` properties, overridable)

**Goal.** Auto-configure the new beans, conditional and overridable, with a kill-switch.

**Deliverables.**
- `OpaAbacProperties` gains a `partialEval` group: `enabled` (default `true`), `allowlistFallback`
  (default `true`). Regenerate `spring-configuration-metadata.json`.
- `OpaAbacAutoConfiguration` registers `ResidualSpecificationFactory` and `AbacQueryService`
  (`@ConditionalOnMissingBean`; security-independent — they need only JPA on the classpath, so guard
  with `@ConditionalOnClass(JpaSpecificationExecutor.class)`). Inject the existing `OpaClient`.
- Do **not** register a `SecurityFilterChain` or alter existing beans.

**Acceptance.** `./gradlew :opa-abac-spring-boot-starter:test` green. `ApplicationContextRunner`:
beans present with JPA on classpath + `enabled=true`; absent without JPA (`FilteredClassLoader`) or with
`partialEval.enabled=false` for `AbacQueryService` short-circuit; user-supplied `ResidualSpecificationFactory`
/ `AbacQueryService` override; properties bind.

**What NOT to touch.** core/security public APIs; the security beans; any existing property default.

---

## T6 — Example adoption: `JpaSpecificationExecutor`, filtered list handlers, rego filter entrypoint

**Goal.** Make the catalog list endpoints return only the rows the subject may see, filtered in SQL.

**Deliverables.**
- `CategoryRepository`, `ProductRepository`, `CatalogRepository` extend `JpaSpecificationExecutor<…>`
  (additive; existing finders unchanged).
- `CategoryController.listCategories` / `ProductController.listProducts` / `CatalogController.listCatalogs`
  build the query-context (subject from `SecurityContextHolder`, `action=<type>:read`, `resourceType`,
  resource **unknown**) and call `AbacQueryService.findAuthorized(repo, scope, ctx)` where `scope` is the
  existing path filter (`catalogId`/`categoryId`). The coarse `@OpaPreAuthorize(<type>:read)` stays as the
  type-level gate; the residual is the which-rows layer inside it (this is **layer 3 of ADR
  [[0006-three-layer-enforcement-model|0006]]**; the coarse gate is layer 2).
  - **⚠️ AND, don't replace.** The residual `Specification` is **AND-ed with** the existing scoping (e.g.
    `findByCategoryId(categoryId)` becomes `where(scopedToCategory).and(residual)`). Swapping the scoped
    finder for a bare `findAll(residual)` would drop the path scoping and **leak cross-scope rows**.
  - **Resolve the role on the governing parent** the same way the shipped `CategoryAuthorizer` does
    (catalog→category), so the list and a single-GET agree on which rows are visible.
- `infra/opa/policies/category.rego`: add a **`filter`** rule (partial-eval entrypoint) whose body
  expresses the tag grant while leaving `input.resource` symbolic — reuse the [[TAG-DICTIONARY]]
  `tags_satisfied` shape, written so `opa eval --partial --unknowns input.resource` returns row residuals.
  - **⚠️ Role-definition-only — drop the subject-roles fallback.** The `filter` rule must **not** inherit
    the `allow` rule's `not has_role_definition → grant from JWT roles` fallback. A list request with no
    role definition must compile to **`DENY_ALL` (empty list)**, never `ALLOW_ALL` (the whole table leaks).
    Model `filter` on `team.rego` (which already dropped the fallback). Fail *closed*.
  - **Flat-verb only (sequencing).** `filter` matches the **current flat `category:read` verb** — it is
    **not** category-aware. Category expansion (`READ`/`WRITE`/`TAG`/`GRANT`, `expand-minus-deny`, table in
    OPA `data`) is Phase 6.5 / ADR [[0007-coarse-grained-permission-categories|0007]], retrofit later
    (additive — flat tokens keep deciding). Do not anticipate categories now.
  Add a **`bulk`** rule (list input → `[allow per item]`) for `allowAll` (the shared primitive — see T2).
  The `allow` rule is **unchanged**. Mirror the policy into both source dirs (the service
  `resources/opa/policies/` source-of-truth + the rig's `infra/opa/policies/`), restart OPA after editing
  (mx — `--watch` doesn't always reload).
- `opa test`: keep all existing cases green; add `filter`/`bulk` cases incl. partial-eval assertions —
  including a **no-role-definition → empty residual (fail-closed)** case for `filter`.

**Acceptance.** `./gradlew build` green; `ddl-auto: validate` clean (no schema change — `tags` + GIN
index already exist). `opa test` all green incl. new cases. With the rig up, a tag-gated subject's
`GET …/categories` returns only matching-tag rows (manual check; automated in T7); a no-role-def request
returns `[]`, not the full list.

**What NOT to touch.** DB columns/migrations (none); OpenAPI spec (the list response shape is unchanged —
it returns fewer items, not a different schema); the single-decision policy rules; `gateway.rego`. The
`filter` rule is **flat-verb** — no category tokens (that's Phase 6.5).

---

## T7 — e2e list-filtering matrix + docs + roadmap/Mulch

**Goal.** Prove, through the gateway, that two subjects get different row sets from the same list endpoint,
and that the cut is in SQL. Then document and record.

**Deliverables.**
- `scripts/postman/run-filter-matrix.sh` + `data-filter-matrix.postman_collection.json`: seed (≥3
  differently-tagged Categories under one Catalog) + two tag-gated reader tokens + one allow-all token,
  minted in-network. Assert: reader A's list = only A's rows; reader B's list = only B's rows (disjoint or
  overlapping per tags); allow-all = all rows; an empty-grant subject = `[]`. Use run-unique seed keys
  (mx — define endpoints aren't idempotent). Update `local.postman_environment.example.json`,
  `scripts/postman/README.md`.
- Docs: `docs/guides/DATA-FILTERING.md` (the mechanism + adoption recipe + the fail-closed/allowlist
  edge); update `docs/architecture/` with a partial-eval section; `infra/README.md` (the filter matrix).
- `POC-ROADMAP.md`: mark Phase 5 done (note batch+partial shipped; hierarchical ancestor-walk + ReBAC
  still ahead). Move `DATA-FILTERING/` → `docs/to-do/implemented/` with a "Shipped" banner.
- Mulch: record durable insights (compile-API→DNF residual fail-closed; residual→JSONB `Specification`
  translation + `?`/`jsonb_extract_path_text`; the allowlist-finisher two-layer; the abstract-not-default
  client methods rationale; the unsupported-residual→deny-or-batch safety property). `ml sync`
  (`.mulch/`-only); `ml doctor` clean.

**Acceptance.** Rig up → `run-filter-matrix.sh` green (different subjects, different row sets, SQL cut);
`bash -n` clean; JSON valid; docs/roadmap/Mulch updated; **clean-room scan clean**. **No push.**

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green (all modules + example + ITs + OpenAPI codegen).
- `opa test` green (existing + new `filter`/`bulk` cases).
- `ddl-auto: validate` clean — **no schema change** (the `tags` JSONB + GIN index already shipped).
- **Fail-closed proven** at every layer: compile error → `DENY_ALL` → empty page; batch error → all-false;
  unsupported residual → deny or exact batch re-check; **a list with no role definition → empty (the
  `filter` rule has no subject-roles fallback)** — **never "return everything"**.
- The residual `Specification` is **AND-ed with** the existing path scoping (never replaces the scoped
  finder) — no cross-scope row leak.
- **`opa-abac-core` stays Spring-free** (the residual model + Compile-API call carry no JPA/Spring import).
- The single-decision `@OpaPreAuthorize` path + the `allow` rego rule are **byte-for-byte unchanged**;
  `compile`/`allowAll` are purely additive to `OpaClient` (the only mechanical cost is converting the three
  test `OpaClient` impls — two are lambdas — to implement the widened interface; see T1).
- The `filter` rego rule is **flat-verb** (no category tokens — category expansion is Phase 6.5 / ADR 0007).
- **Clean-room scan clean** on all new code + docs — the project's standard scan (proprietary
  org/platform/package names, the corporate token prefix, local home paths, and source ticket-ids; the
  exact pattern is in the [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] hard rules and root `CLAUDE.md`) returns empty.
- Commit identity `Void3110 <void31102025@gmail.com>`; one focused commit per ticket; **no push**.
