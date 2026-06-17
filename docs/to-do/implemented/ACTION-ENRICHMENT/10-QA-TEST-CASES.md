---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# 10 — QA test cases: Action enrichment (Phase 6)

> The concrete cases each [[01-DECOMPOSITION|ticket]]'s *Acceptance* references. **U** = unit
> (core / advice / starter — no DB, no rig), **I** = integration (catalog/user-mgmt service, real-Postgres
> Testcontainers + the in-process `com.sun.net.httpserver.HttpServer` OPA stub — no WireMock), **E** =
> e2e through the gateway (newman). This is an **affordance** slice (read-side): the cases assert *what
> the `_actions` map contains*, the three fail-closed semantics, and that enforcement + data-filtering are
> untouched. There is **no policy (P) section** — no change to existing *decision logic*. *(Correction,
> 2026-06-17: the original "zero Rego change" premise was wrong — `bulk` existed only in `category.rego`,
> so the slice **added** the identical decision-preserving `bulk` entrypoint to `catalog`/`product`/`team`
> rego with mirrored `opa test` cases. OPA must therefore be reloaded on first pull. See ADR 0016 §6.)*

## Conventions

- **Unit (advice):** drive `ActionEnrichmentAdvice.beforeBodyWrite` directly with stub DTOs implementing
  `Enrichable` (a test `<Type>Enrichable` with fixed `abacResourceType()`/`abacActions()`); a Mockito or
  in-process-`HttpServer` `OpaClient` returning a programmable positional `List<Boolean>`; an
  `AbacResourceCache` test double pre-populated per case. The refold cases assert the exact
  `Map<verb,Boolean>` per row.
- **Unit (starter):** `ApplicationContextRunner` — bean presence under the property + `@ConditionalOnWebApplication`.
- **Integration:** `ActionEnrichmentIT` / `ActionEnrichmentListIT` extend the `AbstractPostgresIT`
  pattern (**real Postgres via Testcontainers**, never H2) with the **programmable context-aware
  `OpaClient` stub** that decides each `(resource, action)` from `input.resource.attributes` (so the map
  provably reflects *resolved* state). Query-count assertions use the existing IT hook (the 5.97
  no-second-SELECT pattern).
- **e2e:** full rig — `./profile.sh up` → `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`;
  `./deploy.sh build` to force new images; mint tokens **in-network** (issuer `keycloak:8888`). **OPA
  must reload** — the slice added the `bulk` entrypoint to `catalog`/`product`/`team` rego, so the runner
  restarts the OPA container itself (additive — no decision change, the existing matrices stay
  byte-identical). Fixtures: a dedicated set under a registered catalog id (shared fixtures must not grow).
- **The pinned contract (ADR [[0016-action-enrichment-affordance-metadata|0016]] + the three
  decomposition-pinned semantics):**

  | Semantic | Pinned |
  |---|---|
  | `bulk` error / timeout / mixed-type | **omit `_actions`** for the affected rows — never an all-`false` map |
  | Cache miss for a row | **omit `_actions`** for that row; other rows still enriched |
  | Ancestor / role-resolution (`RoleResolutionException`) failure for a row | **omit `_actions`** for that row |
  | `_actions` present | a **complete** map — every `abacActions()` verb keyed, real `true`/`false` |
  | `_actions` absent | enrichment could not be computed — the client decides client-side |
  | Verbs enumerated | **only fully-OPA-decided** — team's `change-role`/`define-roles`/`transfer-ownership` excluded |
  | Cache | an **attribute snapshot, never a verdict**; gate still never reads it; list survivors written post-filter |
  | Keys | **bare verbs** (`view`/`update`/…); the type prefix is implicit |
  | Batch | reuse `allowAll` verbatim; one `bulk` per resource type; **zero `OpaClient`/Rego change** |
  | Kill-switch off / non-web / no `Enrichable` DTO | **byte-identical** — no `_actions`, no list write-through |

---

## Unit — core relocation + marker (T1)

| # | Case | Expected |
|---|------|----------|
| **U1** | `AbacResourceCache` relocated to `opa-abac-core`; `RequestAttributesResourceCache` stays in spring-security and `implements` it; `Enrichable` declared | `./gradlew build` green — every consumer recompiles against `…core.AbacResourceCache` (catalog handlers, `ResourceResolutionSupport`, the 5.97 auto-config, test stubs), **no behavior change** (existing 5.97 cache/manager tests pass unmodified bar the import). Import-set proof: the interface + `Enrichable` carry **no Spring import** in core |

## Unit — the advice (T2)

| # | Case | Expected |
|---|------|----------|
| **U2** | a non-`Enrichable` return (e.g. a `String` / an error body) | `supports`=false / passthrough — body byte-identical, no OPA call |
| **U3** | **The P×V refold:** a 3-row page, V=4 verbs, stubbed `allowAll` returns a known 12-bool list | each row gets its own `Map<verb,Boolean>` with the correct verb→bool (row *i*, verb *j* = index *i·4+j*); keys are **bare verbs** |
| **U4** | a single `Enrichable` resource, cache hit, all verbs allowed | `_actions` = every verb `true`; one `allowAll` call with V contexts, all carrying the resolved `(type,id,attributes,ancestors)` + the re-qualified `"type:verb"` action |
| **U5** | **Honest `false`:** stub denies `update`/`delete`, allows `view` | `_actions = {view:true, update:false, delete:false, …}` — a complete map with real denials (the headline value) |
| **U6** | **`bulk` throws / returns a short list** | **every affected row's `_actions` stays unset** — no all-`false` map emitted; the response body is otherwise unchanged (omit-on-failure, semantic #1) |
| **U7** | **Cache miss for one row of a multi-row page** | the missed row's `_actions` unset; the **other rows are still enriched** (per-row degrade, not all-or-nothing) |
| **U8** | **Ancestor-supplier throws / `RoleResolutionException` for a row** | that row's `_actions` unset (omit) — never a partial map, never a wider map |
| **U9** | the cache is read as a **snapshot, not a verdict** | pre-populate the cache with an instance; the advice still issues a fresh `allowAll` for every verb (presence never short-circuits to "all allowed") — semantic #3 |

## Unit — list-path write-through (T3)

| # | Case | Expected |
|---|------|----------|
| **U10** | `AbacQueryService` with a cache collaborator present, on each of the three paths (pure-SQL / allowlist-batch / kill-switch) | every **survivor** row written to the cache keyed `(type,id)` before return; **denied/dropped rows never written**; cache **absent** → no write, no NPE, return value byte-identical |

## Unit — starter composition (T4)

| # | Case | Expected |
|---|------|----------|
| **U11** | defaults, web app | `ActionEnrichmentAdvice` bean present; `AbacQueryService` wired **with** the cache collaborator |
| **U12** | `opa.abac.action-enrichment.enabled=false` | **no** advice bean; `AbacQueryService` wired **without** the cache collaborator (write-through dormant) — the byte-identical rollback path |
| **U13** | non-web app **and** a user-supplied `ActionEnrichmentAdvice` bean | non-web → no advice; user bean → overrides the default (`@ConditionalOnMissingBean`); `spring-configuration-metadata.json` carries `opa.abac.action-enrichment.enabled` (default `true`) |

## Integration — catalog service, real Postgres (T5; the write-through is T3)

| # | Case | Expected |
|---|------|----------|
| **I1** | **No second SELECT:** `getCategory` enrichment after the gate cached the snapshot; a `listCategories` page after the T3 write-through | the advice reads each row from the cache — assert the repository was **not** queried again for the enriched rows (query-count hook) |
| **I2** | a `CategoryPage` of N rows, mixed per-row tags, one editor subject | each element's `_actions` reflects **its own** tags — tag-matched rows allow `update`, tag-mismatched rows deny it (the map mirrors enforcement per row) |
| **I3** | **The honest map (headline):** a read-only subject GETs a category | `_actions = {view:true, update:false, delete:false, assign-tags:false}` — same row, true-and-false in one map |
| **I4** | a deep `product` under a tree where the role is granted on the **governing root** | the product's `_actions` reflects the root role (inherited grant) — the advice resolved ancestors and looked the role up on `ancestors[0]` |
| **I5** | the generated DTOs (`Catalog`/`Category`/`Product`) | each `implements <Type>Enrichable`; `_actions` round-trips through Jackson on the wire as the key **`_actions`** (a `readOnly` `Map<String,Boolean>`); the accessor name matches `Enrichable.getActions/setActions` (codegen-fit confirmed, pinned in STATUS-05) |
| **I6** | **catalog/product verb sets (verified):** enrich a `Catalog` and a `Product` | `_actions` keys are exactly `[view,update,delete]` — **no `assign-tags`** (no such endpoint); `Category` keys include `assign-tags` |

## Integration — user-mgmt service, real Postgres (T6)

| # | Case | Expected |
|---|------|----------|
| **I7** | **The team OPA-decided subset + the ungated-read degrade:** a `Team` response via the ungated `getTeam`; a path where a team *is* in the cache | the `team` `abacActions()` is exactly `[list-members,add-member,remove-member]` (never `change-role`/`define-roles`/`transfer-ownership`); on the **ungated `getTeam`** the team cache-misses → `_actions` **absent** (the visible degrade); `Membership`/`MembershipPage` carry no `_actions` (unenriched, by design) |

## e2e — through the gateway (T6)

| # | Case (live, through APISIX) | Expected |
|---|------|----------|
| **E1** | **Viewer vs editor contrast:** two subjects `GET /catalogs/{c}/categories/{id}` | the viewer's `_actions` = `{view:true, update:false, delete:false, assign-tags:false}`; the editor's allows `update`/`delete`/`assign-tags` on a tag-matched category — the **decision contrast**, asserted key-by-key |
| **E2** | **Per-row on a page:** `GET /catalogs/{c}/categories` (a `CategoryPage`) as an editor over mixed-tag rows | each `items[i]._actions` present and complete; a tag-matched row allows `update`, a tag-mismatched row denies it |
| **E3** | **The team subset:** `GET /teams/{id}` (ungated) | response `200`; `_actions` **absent** (ungated → cache-miss degrade); the contract that a team, *when enriched*, would show only the three OPA-decided verbs is covered by I7 |
| **E4** | **Omit-on-failure, live:** force the enrichment `bulk` call to error (OPA stub for the enrichment path down / a forced 5xx) while the handler's own gate still passes | the response is still **`200`** with the resource body intact and `_actions` **absent** — never an all-`false` map, never a 5xx from enrichment |
| **E5** | **Affordance ≠ enforcement:** an action the map reports `false` is still independently denied by the real gate when attempted | the `_actions:false` matches a real `403` on the corresponding mutation — the map mirrors, never replaces, enforcement |
| **E6** | **Suite-wide coexistence:** every existing `run-*.sh` matrix (team/tag/filter/hierarchy/pagination/permission-categories/control-plane + `catalog-e2e`) | green — row counts and decisions **numerically unchanged**; `_actions` is a purely additive field on enriched responses; enforcement untouched |

## Docs (T7)

| # | Case | Expected |
|---|------|----------|
| **D1** | the new guide `docs/guides/ACTION-ENRICHMENT.md` | exists, valid frontmatter; carries the adoption recipe (`x-implements` + `_actions` + the sub-interface) and the three semantics + the caveats (affordance-not-enforcement, kill-switch, team Java-co-gated exclusion, `Membership` unenriched, ungated-`getTeam` degrade) |
| **D2** | reconciliations | [[ABAC-AUTHORIZATION]] adds affordance as a read-side layer (ADR 0006 body untouched); [[PARTIAL-EVALUATION-FILTERING]] notes the list-path write-through; [[REST-API-DESIGN]] notes the `_actions` envelope; `infra/README.md` + [[E2E-TESTING]] list the new matrix |
| **D3** | the record | [[USER-STORIES]] "buttons" epic ✅; [[POC-ROADMAP]] Phase 6 shipped, next B3; index table ticked T1–T7; folder moved to `implemented/` with the Shipped banner |

## Fail-closed checklist (must all hold — nothing widens)

- [ ] **Omit, never fabricate.** Bulk error / cache miss / ancestor / role failure → `_actions` **unset**
      for the affected rows (U6/U7/U8, E4); there is no code path that emits an all-`false` (or any
      synthesized) map on a failure.
- [ ] **A present map is complete + honest.** Every `abacActions()` verb is keyed with a real verdict
      (U4/U5, I3); `false` means a real deny, computed fresh from `bulk`.
- [ ] **Only OPA-decided verbs.** The team set excludes the Java-co-gated escalation verbs (I7); no
      enumerated verb is structurally unreachable (catalog/product exclude `assign-tags`, I6).
- [ ] **The cache can't widen a verdict.** Read as a snapshot only; presence never implies "allowed"
      (U9); list survivors written post-filter, denied rows never written (U10); the gate still never
      reads the cache.
- [ ] **The opt-out is byte-identical.** Kill-switch off / non-web → no advice, no write-through, no
      `_actions` (U12); the whole existing suite numerically unchanged (E6).
- [ ] **Affordance ≠ enforcement.** The advice never blocks a request; a `_actions:false` matches a real
      gate `403` (E5); enforcement decisions and data-filtering row sets are out of the diff
      (grep `git diff --name-only` — no `@OpaPreAuthorize` value change, no `findAuthorized` decision change).
- [x] **Zero `OpaClient` change / no decision change.** `allowAll` reused verbatim; `OpaClient` signatures
      unchanged. *(Correction, 2026-06-17: not "zero Rego" — the `bulk` entrypoint was **added** to
      `catalog`/`product`/`team` rego, mirroring `category.rego`; it adds no new decision, so `opa test`
      stays green and the existing decision tests are unmodified — but OPA must reload. ADR 0016 §6.)*

## Related

- [[01-DECOMPOSITION]] (the tickets these cases gate) · [[00-DESIGN]] (§4 behavior matrix, §6 proof
  obligations) · ADR [[0016-action-enrichment-affordance-metadata|0016]] (the pinned forks) ·
  ADR [[0013-attribute-rich-pre-authorization|0013]] (the cache reused) ·
  ADR [[0005-partial-eval-to-jpa-specification|0005]] (the `allowAll` primitive).
- The shipped templates: `docs/to-do/implemented/RESOURCE-RESOLUTION/10-QA-TEST-CASES.md` (the SPI +
  cache + fail-closed shape) · `docs/to-do/implemented/DATA-FILTERING/10-QA-TEST-CASES.md` (the
  `allowAll` batch + fail-closed checklist shape).
