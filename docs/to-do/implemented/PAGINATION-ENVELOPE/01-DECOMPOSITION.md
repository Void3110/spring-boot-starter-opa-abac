---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
  - area/abac
---

# 01 — Decomposition: Pagination envelope (Phase 5.95)

> The ordered work list for [[PAGINATION-ENVELOPE|Slice 5.95]], decomposed from [[00-DESIGN]] +
> ADR [[0012-pagination-envelope|0012]] + the [[REST-API-DESIGN-REVIEW]] (finding #5).
> **6 tickets, one focused commit each.** Each ticket's *Acceptance* references a case in
> [[10-QA-TEST-CASES]]. Packages: library under `dev.dmitriikonovalov.opaabac.data.filter`; example
> services under `dev.dmitriikonovalov.example.{catalog,usermgmt}.web` (+ generated
> `…openapi.model.<Resource>Page`).
>
> **Critical path: T1 → (T2 ∥ T3 ∥ T4) → T5 → T6.** T1 is the prerequisite for everything (the paged
> seam). T2 (library IT), T3 (catalog), T4 (user-service) are **independent of each other** once T1
> lands. T5 (e2e) needs both services emitting the envelope (T3 + T4). T6 is docs + roadmap + Mulch +
> the folder move. **T1 + T2 are independently landable** — the paged `findAuthorized` overload is
> reusable library value with no app dependency (purely additive; both example modules build green
> against the unpaged overloads until they adopt in T3/T4).

This slice is a **list-shape** change, not a decision-logic one. **There is no authorization behavior
change anywhere**: only `listCategories` flows through the (now paged) residual path, exactly as today;
every other list keeps its `@OpaPreAuthorize` gate over plain Spring Data pagination. **Zero Rego
changes** (`infra/opa/policies/` untouched — `opa test` stays as-is). **`opa-abac-core` is not touched**
(`Pageable`/`Page` enter only in `opa-abac-spring-data`, which already depends on Spring Data). The wire
change is a **clean break**: bare arrays are replaced by the envelope in both specs and the newman
collections update in the same slice (T5).

### Where each piece lands (decided — see [[00-DESIGN]] §5 "Where the pieces live")

```
opa-abac-spring-data (…opaabac.data.filter):
    AbacQueryService        + findAuthorized(repo, scope, queryContext, subtreeSpec, Pageable): Page<T>
                              (additive 5-arg overload; unsorted-Pageable guard; all four paths)
    PaginationListIT        new real-Postgres IT — two-subject count + the stability walk
example-catalog-management-service (…example.catalog):
    openapi/catalog-api.yaml   PageEnvelope + CatalogPage/CategoryPage/ProductPage (allOf) +
                               components/parameters Page,PerPage; list ops gain the params
    web/{Catalog,Category,Product}Controller   build PageRequest(page, perPage, DEFAULT_ORDER);
                               map Page<T> → <Resource>Page
    config/CategoryListAuthorizer   readable(...) gains a Pageable pass-through → the paged seam
example-user-management-service (…example.usermgmt):
    openapi/user-mgmt-api.yaml  PageEnvelope + User/Team/Membership/RoleDefinition/TagDefinition Page
                               schemas + the params; six list ops gain them
    service/{Membership,RoleDefinition,TagDefinition}Service + the User/Team list paths   Pageable→Page<T>
    web/*Controller            PageRequest + envelope mapping; /internal note: unpaginated by design
infra/docs:
    scripts/postman/run-pagination-matrix.sh + pagination-matrix.postman_collection.json   NEW matrix
    scripts/postman/*          UPDATE existing collections' list assertions to the envelope
    docs/guides/REST-API-DESIGN.md (§7 adopted, §9 row moved) ·
    docs/guides/PARTIAL-EVALUATION-FILTERING.md (paged composition) · docs/guides/E2E-TESTING.md ·
    POC-ROADMAP · Mulch
```

---

## T1 — Library: paged `findAuthorized` overload (all four paths) + the unsorted-`Pageable` guard + unit tests

**Goal.** Ship the paged seam: an **additive** 5-arg overload
`findAuthorized(JpaSpecificationExecutor<T> repo, Specification<T> scope, AbacContext queryContext, Specification<T> subtreeSpec, Pageable pageable)`
returning `Page<T>`, correct on **all four** query paths, refusing nondeterministic paging. The reusable
core of the slice — **independently landable** (pure library + unit tests, no app change).

**Deliverables.**
- **The 5-arg overload** in `AbacQueryService` (`opa-abac-spring-data`). Behavior per path, mirroring the
  unpaged composition exactly (same specs, same order of checks — [[00-DESIGN]] §3):
  - **Guard first:** `pageable.getSort().isUnsorted()` (which includes `Pageable.unpaged()`) →
    `IllegalArgumentException` with a message naming the rule ("paged findAuthorized requires a sorted
    Pageable — pagination without a total order is nondeterministic"). Fail-loud at dev time.
  - **Kill-switch path** (`!settings.enabled()`): `opaClient.allow` deny → `Page.empty(pageable)`;
    allow → `repo.findAll(scopeOnly(scope).and(notDenied()), pageable)`.
  - **`fromError`:** → `Page.empty(pageable)` (no repo call — the fail-closed cut, `count = 0`).
  - **Allowlist-fallback path** (`!fullySupported() && allowlistFallback()`): fetch **all** scoped
    candidates **SQL-sorted** — `repo.findAll(scopeOnly(scope), pageable.getSort())` — then
    `batchFilter(...)` (order-preserving, unchanged), then slice the requested page from the filtered
    list and return `new PageImpl<>(slice, pageable, filtered.size())`. Past-the-end offset → empty
    content, exact total. *(Cost unchanged from Phase 5 — the path is fetch-all today; the in-memory
    slice is what makes `count` exact. ADR 0012 §1.)*
  - **Pure-SQL path:** the identical `combined = scope.and(tagResidual.or(subtreeSpec)).and(notDenied())`
    composition, then `repo.findAll(combined, pageable)` — Spring Data issues the `COUNT` over the same
    specification.
- **Javadoc** on the overload: the exact-count semantics per path, the guard's rationale, and the
  fallback's documented cost (mirror the existing class-level doc style).
- **Unit tests** (`AbacQueryServiceTest`, the existing programmable-stub `OpaClient` + mock-repo pattern):
  **U1–U8** in [[10-QA-TEST-CASES]] — pure-SQL paged query + count; the guard (unsorted + unpaged);
  fallback slice/count/order/past-the-end; kill-switch both branches; `fromError` empty page; the
  existing 3-arg/4-arg overloads untouched (regression: every existing test green, unmodified).

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green — the new U1–U8 cases pass **and every
pre-existing test passes unmodified** (the overload is additive). No app module changed in this ticket.

**What NOT to touch.** `opa-abac-core` (untouched — `Pageable`/`Page` stay in `-spring-data`). The
existing 3-arg/4-arg overloads' signatures and behavior (byte-compatible; the new overload delegates to
nothing that changes them). `ResidualSpecificationFactory`, `notDenied()`, `batchFilter` semantics (the
paged paths *reuse* them — same specs, same fail-closed contracts; if you think one needs a non-additive
change, STOP and report). The example services (T3/T4 adopt). No Rego, no schema.

> **Build-breaker: none.** T1 is purely additive — a new overload only. Both example modules and all
> existing library tests compile and pass unchanged. **T1 lands green alone.**

---

## T2 — Library IT (real Postgres): `PaginationListIT` — the two-subject count + the stability walk

**Goal.** Prove the paged seam against real Postgres: the count is subject-relative, pages walk the
authorized set exactly once, the fallback path pages correctly, and past-the-end is empty-with-exact-count.
The slice's **decisive correctness evidence** (the determinism regression test lives here).

**Deliverables.**
- **`PaginationListIT`** (`opa-abac-spring-data/src/test/…/filter/`, Testcontainers — follow the
  `AbacQueryServiceIT` / `HierarchyListFilterIT` setup: real Postgres, the in-process
  `com.sun.net.httpserver.HttpServer` OPA stub, the module's existing test entity + repository):
  - **I1 — two subjects, same data, different `count`:** seed rows with contrasting tags; subject A's
    residual matches 5, subject B's matches 3 → `Page.totalElements` 5 vs 3 on the same scope, page
    contents disjoint per the residuals.
  - **I2 — the stability walk:** walk all pages at `perPage=2` (sorted `createdAt ASC, id ASC`); assert
    the **union of pages == exactly the authorized set, no row repeated, none dropped** — this is the
    test that catches a nondeterministic-order regression.
  - **I3 — fallback-path paging:** stub a **not-fully-SQL** residual (the `unsupported()` compile answer)
    with the allowlist on; assert the page slice + exact `count` + the same `createdAt,id` order as the
    pure-SQL path would give (path-independent ordering).
  - **I4 — past-the-end:** request a page beyond the last → empty content, `totalElements` still exact.
- Reuse the existing seeding/stub helpers; new fixtures stay local to this IT (no shared-fixture changes).

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green including `PaginationListIT` (I1–I4), under
the podman/Testcontainers setup the module already uses. Existing ITs (`AbacQueryServiceIT`,
`HierarchyListFilterIT`, …) stay green unmodified.

**What NOT to touch.** The library main code (T1 froze it — if the IT reveals a real bug, fix it as an
amendment to the T1 surface *in this commit* and say so in `STATUS-02.md`). Existing ITs and their
fixtures. The example services. No Rego.

> **Build-breaker: none.** Test-only ticket, confined to `opa-abac-spring-data`'s test sources.

---

## T3 — Catalog: spec envelope + paged controllers + `CategoryListAuthorizer` pass-through + IT

**Goal.** Adopt the envelope in the catalog service: the spec's three list ops gain `page`/`perPage` and
return `<Resource>Page`, the controllers build the fixed-order `PageRequest` and map `Page<T>` → the
generated envelope, and the categories list passes the `Pageable` through to the paged residual seam.
Independent of T2/T4 once T1 has landed.

**Deliverables.**
- **`openapi/catalog-api.yaml`** — add `PageEnvelope` (`count` int64 / `page` / `perPage`, all required,
  bounds as schema constraints per [[00-DESIGN]] §4), the three `allOf` compositions (`CatalogPage`,
  `CategoryPage`, `ProductPage`, each with required `items`), and shared
  `components/parameters/Page` + `PerPage` (`page`: int, `minimum: 0`, `default: 0`; `perPage`: int,
  `minimum: 1`, `maximum: 100`, `default: 20`). Repoint `listCatalogs`/`listCategories`/`listProducts`
  responses from the bare arrays to the `<Resource>Page` schemas and add the two params to each.
- **Controllers** — `CatalogController.listCatalogs`: `catalogs.findAll(pageable)`;
  `ProductController.listProducts`: a derived `findByCategoryId(UUID, Pageable)` returning `Page<…>`;
  `CategoryController.listCategories`: pass the `Pageable` to the authorizer. Each builds
  `PageRequest.of(page, perPage, DEFAULT_ORDER)` where `DEFAULT_ORDER = Sort.by("createdAt").ascending()
  .and(Sort.by("id").ascending())` — one shared constant for the service (placement free, e.g. a small
  `web/PageDefaults`). Map `Page<T>` → the generated envelope (`count = totalElements`, `page`/`perPage`
  echo the request) via a small shared mapper helper. **Authorization untouched:** the three
  `@OpaPreAuthorize` annotations stay byte-identical.
- **`config/CategoryListAuthorizer`** — `readable(catalogId, parentId, pageable)` delegating to the
  **paged** `findAuthorized` (same context, same scope, same `subtreeSpec` resolution — only the overload
  changes). Keep the unpaged variant only if another caller needs it; otherwise migrate the single call
  site cleanly.
- **Validation is contract-driven:** the generated `@Min`/`@Max`/defaults from the spec params carry the
  bounds — violations land on the existing `VALIDATION_FAILED` 400 advice (ADR 0011). **No new error
  codes, no hand-rolled validation.**
- **IT** — **I5–I6** in [[10-QA-TEST-CASES]] (extend the existing catalog IT setup — `CatalogCrudIT` /
  `ErrorContractIT` style): envelope shape + defaults (no params → `page=0`, `perPage=20`); the strict
  negatives (`perPage=101`, `perPage=0`, `page=-1` → `400 VALIDATION_FAILED` `problem+json`);
  past-the-end → `200` + empty `items` + exact `count`.

**Acceptance.** `./gradlew :example-catalog-management-service:build` green (codegen regenerates the API
interfaces with the new params + return types — drift = build break; **C1–C2**). I5–I6 pass. Existing
catalog ITs stay green **except** assertions that consumed a bare list response — update those to
`items` in this commit (they are part of the break, not collateral).

**What NOT to touch.** `opa-abac-core`, the library modules (consume T1 read-only). The user-service
(T4 — independent). **Authorization shape:** `listCatalogs`/`listProducts` stay coarse-gated
(`@OpaPreAuthorize` + plain queries) — converting them to the residual path is explicitly out of scope
(ADR 0012 §5); `listCategories` stays the only residual list. Single-GET/write endpoints (unchanged).
No Rego, no Liquibase/schema change.

> **Build-breaker (self-contained to this commit).** Changing the three list responses + params in the
> spec regenerates the `CatalogApi` interfaces — the three controllers stop compiling until they adopt
> the new signatures, and any test asserting a bare array response breaks. Update **all** of them in this
> same commit so `./gradlew :example-catalog-management-service:build` stays green. Confined to the
> catalog module (REST-API-REFINEMENT T2 is the model).

---

## T4 — User-service: spec envelope ×6 list ops + paged services/controllers + the `/internal` intent note + IT

**Goal.** Adopt the envelope in the user-service: all six public list ops gain the params and the
`<Resource>Page` returns, the service layer paginates via Spring Data, and the `/internal/**` surface
gets its one-line "unpaginated by design" legibility note. Independent of T2/T3 once T1 has landed.

**Deliverables.**
- **`openapi/user-mgmt-api.yaml`** — the same `PageEnvelope` base + params components as T3 (defined
  per-spec, deliberately — ADR 0012 §2), plus `UserPage`, `TeamPage`, `MembershipPage`,
  `RoleDefinitionPage`, `TagDefinitionPage` (`allOf`). Repoint the six list ops — `listUsers`,
  `listTeams`, `listMembers`, `listRoleDefinitions`, `listTagDefinitions`, `listTeamTagDefinitions`
  (both tag-definition ops return `TagDefinitionPage`) — and add the two params to each.
- **Service layer** — `MembershipService.list`, `RoleDefinitionService.list`,
  `TagDefinitionService.list` (+ the user/team list paths) gain a `Pageable` parameter returning
  `Page<…>` via `findAll(pageable)` / derived `…(UUID teamId, Pageable)` queries. **No
  `AbacQueryService` here** — these lists stay coarse-gated plain queries (authz-nowhere, ADR 0012 §5).
- **Controllers** — the same `DEFAULT_ORDER` constant + `PageRequest` construction + envelope mapping as
  T3 (each service owns its copy of the tiny constant/mapper — the services' builds stay independent).
  **Every `@OpaPreAuthorize` (and every deliberate absence) stays byte-identical.**
- **The `/internal/**` note** — one line at the internal surface's definition site (controller or
  `SecurityConfig`, where §8's network-isolation note already lives): internal endpoints are
  **unpaginated by design** (bounded machine-to-machine payloads) — the same legibility move as 5.9's
  bootstrap comments. **No behavior change; comment only.**
- **IT** — **I7–I8** in [[10-QA-TEST-CASES]]: envelope + defaults on a representative pair of lists
  (one top-level, one team-scoped); one strict-400 negative; one past-the-end. Existing list-consuming
  assertions updated to `items` in this commit.

**Acceptance.** `./gradlew :example-user-management-service:build` green (codegen clean — **C1–C2**).
I7–I8 pass. The `/internal` note present (grep check — I9). Existing user-service ITs green (with the
in-commit `items` assertion updates only).

**What NOT to touch.** `opa-abac-core`, the library modules. The catalog service (T3 — independent).
The `/internal/**` **endpoints' shapes** (plain, unpaginated — the note documents, nothing changes);
the bootstrap mutations (untouched, still ungated-by-design). **No `AbacQueryService` adoption** in this
service — that would be an authorization-shape change. No Rego, no schema.

> **Build-breaker (self-contained to this commit).** As T3: the six regenerated list signatures break
> the five controllers + list-asserting tests until they adopt — all updated in this same commit so the
> module builds green. Confined to the user-service module.

---

## T5 — e2e: the pagination matrix + the fixture set + existing collections moved to the envelope

**Goal.** Prove the contract end to end through the gateway — the subject-relative `count` contrast, a
disjoint paged walk, a live strict-400 — and absorb the clean wire break in the existing newman
collections so the whole suite runs green against the envelope.

**Deliverables.**
- **`pagination-matrix.postman_collection.json` + `run-pagination-matrix.sh`** (follow the
  `run-filter-matrix.sh` / `run-hierarchy-list-matrix.sh` model — dual in-network tokens, collection-scope
  ids, fail-closed asserts): **E1–E3** in [[10-QA-TEST-CASES]] —
  - **E1 the count contrast:** viewer vs editor, same list URL → different `count` (the decisive
    "the count is the count of rows *you* may see" assertion — assert the **numbers**, not just shape);
  - **E2 the paged walk:** `perPage=2` across the pagination fixture set → pages disjoint, union ==
    the single-page set, `count` stable across pages;
  - **E3 the live negative:** `perPage=500` → `400` `problem+json` `errorCode=VALIDATION_FAILED`
    through APISIX.
- **A dedicated, namespaced pagination fixture set** seeded via the internal bootstrap (the fixture-id
  registry convention in `scripts/postman/README.md`): ≥5 categories under one catalog with tags driving
  the two-subject contrast. **Do not fatten shared fixtures** — other matrices assert exact counts on
  them (ADR 0012 §Consequences).
- **Update every existing collection that asserts a list body** (`data-filter-matrix`,
  `hierarchy-list-matrix`, `catalog-e2e`, and the catalog/tag/team matrices where they list): bare-array
  assertions (`json.length`, `[0].…`) → envelope assertions (`json.count`, `json.items.length`,
  `json.items[0].…`). **Keep every existing row-count expectation numerically identical** — the cut must
  not move, only the shape (**E4**).
- **`scripts/postman/README.md`** — the new matrix + fixture-set entry in the registry table.

**Acceptance.** Rig up (fresh clone path: `./profile.sh up` → `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1
./deploy.sh up --pods 2`; `./deploy.sh build` to force the new app images) → `./run-pagination-matrix.sh`
green (E1–E3) **and** every updated existing matrix green with unchanged row-count expectations (E4),
stable across reruns. `bash -n` clean on the new runner; JSON valid. No push.

**What NOT to touch.** The gateway policy / OPA policies (zero Rego in this slice). The app/library code
(T1–T4 frozen; a real contract bug found here is fixed by amending the owning ticket's surface, stated in
`STATUS-05.md` — not by a new behavior). Shared fixture *contents* other matrices count on. No newman CI
job (tracked follow-up).

> **Build-breaker: none.** Test-asset-only (newman JSON + shell + README). No compiled code.

---

## T6 — Docs (guide §7 adopted, §9 row moved) + PARTIAL-EVALUATION-FILTERING + roadmap + Mulch + folder move

**Goal.** Promote pagination from "target" to "the rule", document the paged composition where the
filtering guide lives, flip the roadmap, record the run, and move the slice folder.

**Deliverables.**
- **`docs/guides/REST-API-DESIGN.md`** — rewrite **§7 Pagination** from "today there is none" to the
  adopted convention (the envelope, the params table with defaults/bounds/strict-400/past-the-end, the
  fixed order, the `count` semantics under ABAC); move the §9 *Targets* pagination row into the body with
  an "adopted in 5.95" note (the 5.9 model — leaving `actions`/`Retry-After` as the remaining targets);
  cross-link ADR [[0012-pagination-envelope|0012]]. One §8 line: the internal surface is unpaginated by
  design.
- **`docs/guides/PARTIAL-EVALUATION-FILTERING.md`** — a new "paged composition" section: the four paths'
  paged behavior, the exact-count semantics (incl. the fallback's in-memory slice at unchanged cost), the
  unsorted-`Pageable` guard, the fixed total order.
- **`docs/guides/E2E-TESTING.md`** — the pagination matrix + the fixture-set registry entry.
- **`POC-ROADMAP.md`** — flip **Phase 5.95** to ✅ DONE (envelope shipped, count subject-relative,
  determinism by construction); confirm Phase 6 as next.
- **Mulch** — record the durable insights (the paged-seam pattern: guard + four paths + `PageImpl` over
  the filtered fallback; the spec `allOf` envelope + shared-params model; the e2e envelope-migration
  experience) in `opa-abac` / `spring-data-filtering` / `api-design` as fits; `git restore --staged .`
  then `ml sync`; `ml doctor` clean. **Record the `autonomous-runs` reference record** with
  `--outcome-status` (OUTCOME + PAUSE-CAUSE · FRICTION · PLANNING-GAP→FIX · QA).
- **Move** `docs/to-do/planning/PAGINATION-ENVELOPE/` → `docs/to-do/implemented/PAGINATION-ENVELOPE/`
  (`git mv`), flip the index to `status/done`, add the past-tense **Shipped** banner.

**Acceptance.** The guides read as adopted (§7 is the rule; §9 no longer lists pagination); the roadmap
shows 5.95 done; Mulch synced (`.mulch`-only) + `ml doctor` clean + the `autonomous-runs` record written;
the folder is under `implemented/` with the Shipped banner; clean-room scan clean. No push.

**What NOT to touch.** ADR 0012 (immutable — Accepted; the guide references it). ADR 0011 / the error
contract (the strict-400 reuses it; nothing to change). USER-STORIES D5's text (tick its status only).
No code change in this ticket.

> **Build-breaker: none.** Docs + Mulch + a folder move.

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green end to end (all library modules + both example apps + OpenAPI codegen + every
  Testcontainers IT + `ddl-auto: validate` boot — **no schema change** in this slice, so validate-boot is
  pure regression).
- **Exact-count everywhere:** `count` == the subject's authorized total on the pure-SQL, fallback,
  kill-switch, and error paths (`0` on `fromError`) — never `items.length`, never an estimate (U/I/E
  cases).
- **Determinism by construction:** every paged query carries `createdAt ASC, id ASC`; the unsorted-
  `Pageable` guard throws; the stability walk (I2) proves no repeat/drop; the fallback returns the same
  order as the pure-SQL path (I3).
- **The strict params contract:** 0-based; defaults 0/20; bounds 1–100; violations `400
  VALIDATION_FAILED` `problem+json` via the existing ADR-0011 advice (no new codes, no clamping);
  past-the-end `200` + empty + exact `count` (I6/I8/E3).
- **Authorization semantics nowhere changed:** every `@OpaPreAuthorize` (and every deliberate absence)
  byte-identical; `listCategories` remains the only residual-filtered list; no Rego edit
  (`git diff --stat` shows no `infra/opa/` change); the row sets the e2e matrices assert are
  **numerically unchanged** (E4).
- **Additive library change:** the 3-arg/4-arg `findAuthorized` overloads byte-compatible; every
  pre-existing library test green unmodified; `opa-abac-core` untouched (grep the diff).
- **The clean break is complete:** no public list returns a bare array; both specs declare the envelope;
  no collection still asserts the old shape; `/internal/**` stays plain (with its note).
- **Clean-room scan clean** across all new/changed files; **nothing pushed** (the maintainer pushes).

## Critical path

```
              ┌────► T2 (library IT: count contrast + stability walk) ──┐
T1 ───────────┼────► T3 (catalog: spec + controllers + authorizer + IT) ─┼──► T5 (e2e matrix + ──► T6 (docs + roadmap
(paged seam)  └────► T4 (user-svc: spec ×6 + services + note + IT)      ─┘      envelope break)        + Mulch + move)
```

- **T1 + T2 independently landable** — the paged seam + its Postgres proof are reusable library value
  with no app dependency (additive; the apps still build green unpaged until T3/T4).
- **T2 ∥ T3 ∥ T4** — once T1 lands: the library IT and the two service adoptions touch disjoint modules;
  each service's spec build-breaker is confined to its own commit.
- **T5** needs both services emitting the envelope; it also carries the suite-wide assertion migration
  (the clean break's blast radius lives in exactly one ticket).
- **T6** promotes the guides, flips the roadmap, records Mulch + the retrospective, moves the folder.
