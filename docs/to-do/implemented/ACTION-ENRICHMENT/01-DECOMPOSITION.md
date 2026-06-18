---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# Action enrichment — decomposition (Phase 6)

> The ordered work list for [[ACTION-ENRICHMENT]] (Phase 6 of [[POC-ROADMAP]]). Seven tickets, one
> focused commit each. Design: [[00-DESIGN]]. QA: [[10-QA-TEST-CASES]]. Run via the
> [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]. Pinned by ADR [[0016-action-enrichment-affordance-metadata|0016]]
> (every fork, with rejections — do not reopen).
>
> **Packages.** Library: `dev.dmitriikonovalov.opaabac.{core,security,autoconfigure}` (+ the list-path
> write-through in `…opaabac.data.filter`; **zero `OpaClient` change, zero Rego change**). Example:
> `dev.dmitriikonovalov.example.catalog.*` (T5) and `dev.dmitriikonovalov.example.usermgmt.*` (T6).

## Critical path

```
T1 ──► T2 ──► T4 ──► T5 ──► T6 ──► T7
 └────► T3 ───┘
```

T1 (relocate `AbacResourceCache` to core + the `Enrichable` neutrals) is the root — it unblocks both the
advice (T2) and the list-path write-through (T3). **T2 and T3 are parallel after T1** (T2 is the read
side in spring-security; T3 is the write side in spring-data; they meet at the cache interface T1 moved).
**T4 (starter wiring + kill-switch) needs T2+T3.** T5 (catalog adoption — three types) → T6 (user-mgmt
adoption + the cross-service e2e). T7 closes the record. **T1+T2+T3+T4 are the independently-landable
subset**: the complete library mechanism — opt-in, dormant until an app ships an `Enrichable` DTO + a
resolver/list-write feed. No app behavior changes until T5.

## Three pinned contract semantics (so the run never stops to ask)

These are the fail-closed / contract forks ADR 0016 pinned; each ticket's *What-NOT-to-touch* carries the
one(s) it owns. State them once here so they're never re-litigated mid-run:

1. **Omit `_actions` on ANY failure — never fabricate a map.** A `bulk` error/timeout, a cache miss for a
   row, or an ancestor-resolve failure → the advice **leaves `_actions` unset** for the affected
   resource(s); the response still succeeds with the handler's own status. It **never** emits an
   all-`false` map (a positive "you can't do anything" assertion that lies when the truth is "couldn't
   check," and that a convention-inverting client reads as "show everything"). **`_actions` present ⇒ a
   complete, real per-verb verdict; absent ⇒ enrichment could not be computed.** (ADR 0016 §7.)
2. **Enumerate only fully-OPA-decided verbs (affordance honesty).** A verb belongs in a type's
   `abacActions()` **only if OPA alone decides it** — so `true` means *the caller can actually do it*.
   Java-co-gated verbs are excluded: for `team`, that drops `change-role`, `define-roles`,
   `transfer-ownership` (the 6.7 `MembershipService` escalation gates + the owner-only-by-code fence
   decide the *specific* attempt, which OPA can't see). (ADR 0016 §8.)
3. **The cache is an attribute snapshot, never a verdict.** *Presence ≠ authorized-for-any-action.* The
   list path writes its post-filter survivors as snapshots; every per-action verdict (gate or enrichment)
   is computed fresh from `bulk`. Safe because the gate never reads the cache to decide (the 5.97
   invariant, retained). (ADR 0016 §5.)

## Verified verb sets (checked against real endpoints — see the scout, NOT assumed)

The 00-DESIGN said *verify, don't assume*. Verified against the live `@OpaPreAuthorize` annotations:

| Type | `abacActions()` (VERIFIED) | Correction vs 00-DESIGN expectation |
|---|---|---|
| `catalog` | `["view","update","delete"]` | **`assign-tags` dropped** — no `catalog:assign-tags` endpoint (catalog carries no tags) |
| `category` | `["view","update","delete","assign-tags"]` | confirmed (all four instance-level) |
| `product` | `["view","update","delete"]` | **`assign-tags` dropped** — no `product:assign-tags` endpoint (product carries no tags) |
| `team` | `["list-members","add-member","remove-member"]` | the OPA-decided subset; `change-role`/`define-roles`/`transfer-ownership` exist but are excluded (semantic #2) |

---

## T1 — Core: relocate `AbacResourceCache` to core + the `Enrichable` neutrals (the build-breaker)

**Goal.** Make the cache interface reachable from both `spring-data` (write) and `spring-security`
(read) without a sideways module dep, and give the library the Spring-free contract the advice needs.

**Deliverables.**
- **Move** `AbacResourceCache` (the interface only) from `opa-abac-spring-security`
  (`dev.dmitriikonovalov.opaabac.security`) **to `opa-abac-core`**
  (`dev.dmitriikonovalov.opaabac.core`). Signatures unchanged: `<T> Optional<T> get(String resourceType,
  String resourceId, Class<T> as)` + `void put(String resourceType, String resourceId, Object resource)`.
  Javadoc pins **attribute-snapshot-never-a-verdict** (semantic #3) and "never consulted by a decision."
  - **`RequestAttributesResourceCache` (the impl) STAYS** in `opa-abac-spring-security` (it uses
    `RequestContextHolder`); it now `implements` the relocated core interface.
- **Build-breaker (DATA-FILTERING T1 model):** every reference to `…security.AbacResourceCache` updates
  to `…core.AbacResourceCache` **in this same commit** — the catalog handlers' `cachedCategory`/
  `cachedCatalog`/`cachedProduct` (`example-catalog`), `ResourceResolutionSupport` (`spring-security`),
  the 5.97 auto-config bean type (`autoconfigure`), and any test stub. Grep `AbacResourceCache` across
  the repo; list every touched file in `STATUS-01`. Net behavior change: **zero** (pure relocation).
- `opa-abac-spring-security`, package `dev.dmitriikonovalov.opaabac.security.web` — the marker base:
  - `Enrichable` — `UUID getId()`, `Map<String,Boolean> getActions()`, `void setActions(Map<String,Boolean>)`.
    Javadoc: a resource DTO that may carry an `_actions` affordance map; **stamped onto generated DTOs via
    the OpenAPI `x-implements` extension**; the per-type sub-interface (app-owned, T5/T6) adds
    `default String abacResourceType()` + `default List<String> abacActions()`. *(Consumed by the advice
    in T2; the type sub-interfaces are written in T5/T6.)*

**Acceptance.** QA **U1**. `./gradlew build` green — the relocation recompiles every consumer cleanly,
no behavior change (the existing 5.97 manager/cache tests pass unmodified except the import line). The
import-set proof: `AbacResourceCache` carries no Spring import in core; `Enrichable` carries no Spring
import beyond `java.util`.

**What NOT to touch.** No signature change to `get`/`put` (pure move). The
`RequestAttributesResourceCache` *behavior* (request-attributes storage, no-context no-op). No advice yet
(T2). `opa-abac-core` stays Spring-free (the relocated interface + `Enrichable` are plain Java). No
`OpaClient`/`AbacContext`/Rego change anywhere in the slice.

---

## T2 — spring-security: `ActionEnrichmentAdvice` (the `ResponseBodyAdvice`) + the P×V refold + omit-on-failure

**Goal.** A library advice that recognizes `Enrichable` returns, computes each resource's `_actions` map
from the cached resolved attributes via **one `allowAll` per type**, and writes it inline — degrading to
omission on every failure.

**Deliverables.** Package `dev.dmitriikonovalov.opaabac.security.web`:
- `ActionEnrichmentAdvice implements ResponseBodyAdvice<Object>` (a plain bean registered by the starter
  in T4):
  - `supports(...)` → true for return types assignable to `Enrichable`, `Iterable<Enrichable>`, or a
    **paged envelope** whose `items` are `Enrichable` (the 0012 `PageEnvelope`/`<Resource>Page` shape —
    detect via the generated `getItems()` returning a `List`; `instanceof Enrichable` on the first
    element, never a hard dep on the example DTOs).
  - `beforeBodyWrite(...)`:
    1. Collect the enrichable DTOs (single / `Iterable` / `page.getItems()`); empty → return body
       unchanged.
    2. For each **distinct** DTO: `cache.get(abacResourceType(), getId().toString(), AbacDataObject.class)`
       → the resolved instance. **Cache miss → that DTO is dropped from enrichment** (its `_actions`
       stays unset — semantic #1). Resolve its ancestor chain once via the `AncestorChainSupplier`
       (deduped per distinct `(type,id)`); an ancestor failure → that DTO dropped (omit, never partial).
    3. Build the flat `List<AbacContext>` = rows × `abacActions()` in a **known order** (row-major: row
       *i*, verb *j* → index *i·V+j*), each context = the row's resolved `(type,id,attributes,ancestors)`
       + the re-qualified `"type:verb"` action + the subject + the role resolved **on the governing root**
       (`ancestors.isEmpty() ? (type,id) : ancestors.get(0)` — the 5.97 / `HierarchicalAuthorizer` rule).
    4. **One `opaClient.allowAll(contexts)` per resource type** → positional `List<Boolean>`. A short/
       mismatched list or any thrown exception → **all affected DTOs dropped** (omit; never all-`false`).
    5. Re-fold the flat booleans into per-row `Map<verb,Boolean>` (bare-verb keys) and `setActions(map)`.
  - The subject + role come from the same extraction the gate uses (`AbacContext.Subject` /
    `RoleDefinitionSupplier`); a `RoleResolutionException` (B2) on a row → that row dropped (omit), never
    a wider map.
- The advice depends only on **core** types (`AbacResourceCache`, `AncestorChainSupplier`, `OpaClient`,
  `AbacContext`, `AbacDataObject`) + the `Enrichable` marker — **no dependency on `opa-abac-spring-data`**.

**Acceptance.** QA **U2–U9**. `./gradlew :opa-abac-spring-security:test` green. Headline unit cases: the
P×V refold for a multi-row page maps each row to the right verdicts (U3); **`bulk` throws → every row's
`_actions` unset, no all-`false` map** (U6); **cache miss for a row → that row unset, others enriched**
(U7); a non-`Enrichable` return passes through untouched (U2). OPA stubbed by the in-process
`com.sun.net.httpserver.HttpServer` convention.

**What NOT to touch.** `OpaClient` (reuse `allowAll` verbatim — no new method; ADR 0016 §6). The
`OpaPreAuthorizeAuthorizationManager` gate (the advice is read-side; the gate is unchanged and still
never reads the cache). No `opa-abac-spring-data` dependency. **Carry semantics #1 + #3:** omit on any
failure; the cache read is a snapshot, never read as a verdict. No starter registration yet (T4).

---

## T3 — spring-data: list-path write-through into the cache (all `findAuthorized` paths)

**Goal.** Feed the advice on the list path: every row a filtered query returns is written into the
`AbacResourceCache` as a snapshot, on **all three** query paths, so the advice's single
`cache.get(type,id)` read path serves lists exactly as it serves single-resource reads — with no
double-load and no attribute drift.

**Deliverables.** Package `dev.dmitriikonovalov.opaabac.data.filter`:
- `AbacQueryService` gains an **optional** `AbacResourceCache` collaborator (a constructor overload /
  `@Nullable` field; absent → no write-through, byte-identical to today). When present,
  `findAuthorized(...)` writes each **post-filter survivor row** into the cache keyed
  `(row.abacResourceType(), row.abacResourceId())` **before returning**, on:
  - the **pure-SQL path** (`repo.findAll(authorizedSpec, pageable)` survivors),
  - the **allowlist-batch path** (the `batchFilter` survivors — the rows that got `true`),
  - the **kill-switch path** (`partialEval.enabled=false` → the coarse-allow `findAll` survivors).
  The write is the *same entity instance* the query returns (no re-resolve → no drift). Denied/dropped
  rows are **never** written (consistent with the gate's allow-only write; keeps the cache an
  authorized-snapshot store).
- The write-through is the **only** change to this module's logic; the four `findAuthorized` overloads'
  **return values and authorization decisions are byte-identical** — enrichment is a downstream reader of
  a snapshot, never a re-filter.

**Acceptance.** QA **U10 + I1**. `./gradlew :opa-abac-spring-data:test` green; the list-path write-through
proven by an IT (T5's `ActionEnrichmentListIT`) asserting the advice reads the cached rows with **no
second SELECT** (a query-count assertion). Unit: cache present → survivors written on each of the three
paths; cache absent → no write, no NPE; denied rows never written.

**What NOT to touch.** The residual composition (`scope.and(tagResidual.or(subtreeSpec)).and(notDenied)`),
the allowlist batch decision, pagination (`Page<T>`/exact count), the unsorted-`Pageable` guard, the
`partialEval.enabled` kill-switch — all byte-identical (ADR 0005/0010/0012 unchanged). The write-through
**adds** to the result handling; it never changes which rows are returned. `opa-abac-spring-data` gains
no new module dependency (it already depends on `opa-abac-core`, where the cache interface now lives).

---

## T4 — starter: auto-config wiring + the `opa.abac.action-enrichment.enabled` kill-switch

**Goal.** Register the advice and feed the list-path write-through, gated by the kill-switch — off ⇒
byte-identical to pre-slice (no advice bean, no write-through).

**Deliverables.** Package `dev.dmitriikonovalov.opaabac.autoconfigure`:
- `OpaAbacProperties`: an `actionEnrichment` group with `enabled` (default **true**) — prefix
  `opa.abac.action-enrichment`. Regenerate `spring-configuration-metadata.json`.
- An `ActionEnrichmentAutoConfiguration` nested class —
  `@ConditionalOnProperty(prefix = "opa.abac.action-enrichment", name = "enabled", havingValue = "true",
  matchIfMissing = true)` + `@ConditionalOnWebApplication`:
  - the `ActionEnrichmentAdvice` bean (`@ConditionalOnMissingBean`), wired with the `OpaClient`, the
    `AbacResourceCache`, the `AncestorChainSupplier` (the `ObjectProvider`-bound 5.5 resolver, as 5.97
    already does), the subject extraction + `RoleDefinitionSupplier`.
  - the `AbacQueryService` bean is given the `AbacResourceCache` collaborator **only when enrichment is
    enabled** (so the write-through is dormant when the switch is off) — via the same
    `ObjectProvider`/conditional so a kill-switch-off boot wires the pre-slice `AbacQueryService`.
- With the condition unmet (`enabled=false` or non-web): **no advice bean, the `AbacQueryService` has no
  cache collaborator** → DTOs serialize without `_actions`, the list path does no write-through —
  byte-identical to pre-slice.

**Acceptance.** QA **U11–U13**. `./gradlew :opa-abac-spring-boot-starter:test` green —
`ApplicationContextRunner`: default → advice bean present + `AbacQueryService` has the cache collaborator;
`opa.abac.action-enrichment.enabled=false` → **no** advice bean and the write-through dormant; non-web app
→ no advice; a user-supplied advice bean overrides the default; metadata carries the new property.

**What NOT to touch.** The 5.97 `ResourceResolutionAutoConfiguration`, the
`DataFilteringAutoConfiguration`, `HierarchyAutoConfiguration`, `SecurityAutoConfiguration` beans and
their conditions (additive nested class only). The `resource-resolution`/`partial-eval`/`hierarchy`
property groups. No `SecurityFilterChain` ownership. **Carry semantic #1:** the kill-switch off-state is
the rollback path — prove it's byte-identical.

---

## T5 — catalog adoption: three `<Type>Enrichable` sub-interfaces + three schema blocks + codegen + ITs

**Goal.** The catalog proves the mechanism on all three resource types: the single-GET and the list/page
responses carry honest `_actions`, computed on resolved attributes, batched per type.

**Deliverables.** Example `dev.dmitriikonovalov.example.catalog`:
- Three app-owned sub-interfaces (package `…catalog.security`):
  - `CatalogEnrichable extends Enrichable` → `abacResourceType()="catalog"`,
    `abacActions()=["view","update","delete"]`.
  - `CategoryEnrichable extends Enrichable` → `"category"`, `["view","update","delete","assign-tags"]`.
  - `ProductEnrichable extends Enrichable` → `"product"`, `["view","update","delete"]`.
  *(Sets are the VERIFIED ones — `assign-tags` is present on category only.)*
- `src/main/resources/openapi/catalog-api.yaml`: on the `Catalog`, `Category`, `Product` schemas add
  `x-implements: [ dev.dmitriikonovalov.example.catalog.security.<Type>Enrichable ]` **and** a property
  `_actions: { type: object, additionalProperties: { type: boolean }, readOnly: true }`. Regenerate via
  `org.openapi.generator` (`./gradlew build`) → the generated `Catalog`/`Category`/`Product` POJOs
  `implements <Type>Enrichable` and expose `@JsonProperty("_actions") Map<String,Boolean> actions` +
  getter/setter. **Confirm the generated accessor name** matches what `Enrichable` declares
  (`getActions`/`setActions`); if the generator names it differently, pin the generator naming config in
  this ticket and record it in `STATUS-05` (the "OpenAPI codegen fit" open question — resolve it here).
- The catalog **already registers a `CatalogResourceResolver`** (5.97) and writes single-resource reads
  to the cache; the list path now write-throughs (T3, wired by T4). No mapper change needed — the advice
  sets `_actions` on the already-built DTO after the handler returns.
- ITs (`ActionEnrichmentIT` + `ActionEnrichmentListIT`, real Postgres + the in-process OPA stub):
  single-resource `getCategory` enrichment reads the gate's cached snapshot (no re-load); a list page →
  each element's `_actions` reflects its own tags; **the honest-`false` cell** — a read-only subject's
  `update`/`delete` read `false` while `view` reads `true` on the same row; a deep `product` reflects the
  **governing-root** role; `ActionEnrichmentListIT` asserts the no-second-SELECT (the T3 write-through,
  I1).

**Acceptance.** QA **I1–I6**. `./gradlew build` green (all modules + catalog ITs + OpenAPI codegen). The
generated DTOs implement the markers and round-trip `_actions` through Jackson with the `_actions` wire
key. Headline IT: same row, `view:true` + `update:false` for a read-only subject (I3, the honest map).

**What NOT to touch.** Enforcement — every `@OpaPreAuthorize` annotation byte-identical (enrichment is
read-side; it never gates). The handlers' bodies (the advice runs after return; no handler edits beyond
what the schema regen produces). `assign-tags` is **not** added to catalog/product sets (verified absent).
The OpenAPI **response shapes** stay backward-compatible — `_actions` is additive + `readOnly` (never
accepted on input). **Carry semantics #1+#2+#3.**

---

## T6 — user-mgmt adoption: `TeamEnrichable` (OPA-decided subset) + the cross-service e2e

**Goal.** The second adopter proves the mechanism generalizes to a *different* registry shape (the
control plane) and exercises the affordance-honesty exclusion and the cache-miss degrade on an **ungated**
read; the e2e matrix proves both services through APISIX.

**Deliverables.** Example `dev.dmitriikonovalov.example.usermgmt`:
- `TeamEnrichable extends Enrichable` (package `…usermgmt.security`) → `abacResourceType()="team"`,
  `abacActions()=["list-members","add-member","remove-member"]` — the **OPA-decided subset**
  (`change-role`/`define-roles`/`transfer-ownership` deliberately excluded, semantic #2; a one-line
  comment cites ADR 0016 §8 so the absence reads as a decision).
- `src/main/resources/openapi/user-mgmt-api.yaml`: on the `Team` schema add
  `x-implements: [ …usermgmt.security.TeamEnrichable ]` + the `readOnly` `_actions` property; regenerate.
  **`Membership` is NOT enriched** this slice (the per-membership affordance question — "which actions on
  this member" — is a different registry; documented out of scope). `listMembers` returns
  `MembershipPage`, so it stays unenriched.
- **The ungated-read degrade (the user-mgmt subtlety, verified):** `getTeam`/`listTeams`/`getTeam` are
  **ungated** (owner-on-create bootstrap — no `@OpaPreAuthorize`, so a `Team` is never written to the
  cache by a gate). **Default decision (a), pinned — do not stop to ask:** user-mgmt **registers a
  `TeamResourceResolver`** (`AbacResourceResolver` over the team repo); the advice resolves a team's
  attributes **via the cache only**. Since `getTeam` is ungated, nothing pre-populates the cache for it,
  so a `Team` response **cache-misses and omits `_actions`** — the *visible* degrade (semantic #1), a
  correct and tested outcome (it does **not** re-resolve in the advice — that path is rejected by ADR
  0016). If a later phase gates `getTeam`, enrichment lights up automatically. Record the produced
  behavior in `STATUS-06`. *(This is the one place a micro-decision could arise; it is pre-decided here.)*
- e2e (`scripts/postman`): `action-enrichment-matrix.postman_collection.json` +
  `run-action-enrichment-matrix.sh` — fixture set under a dedicated catalog id (registered in the
  fixture-id registry; shared fixtures must not grow). The cells assert the **map contents**, not just
  shape (QA **E1–E6**): viewer vs editor `GET /categories/{id}` `_actions` (the verb-by-verb contrast); a
  `CategoryPage` with per-row `_actions`; a `team` `GET` showing the omitted/absent `_actions` (the
  ungated-degrade cell) **and** a member-list/affordance cell where a team verb is exercised; a forced OPA
  outage on the enrichment call → the response still `200` with `_actions` **absent** (omit-on-failure,
  live).
- **The whole existing suite green** (every `run-*.sh` matrix): enrichment is additive — no pinned row
  count or decision changes; `_actions` is a new additive field on enriched responses only.

**Acceptance.** QA **I7 + E1–E6**. Rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`;
`./deploy.sh build`; mint tokens in-network) → the new matrix green **and** every existing matrix green.
The catalog cells show the verb-by-verb contrast and never an excluded/unreachable verb; the outage cell
shows `200` + absent `_actions`.

**What NOT to touch.** The user-mgmt enforcement (the `MembershipService` escalation gates, the
owner-only-by-code fence — untouched; their verbs are *excluded* from affordance, not re-decided). The
ungated bootstrap mutations stay ungated (the 5.9 intent comments stand). `Membership`/`MembershipPage`
stay unenriched. **Carry semantics #1+#2+#3.** No Rego change (zero — `bulk`/`allow` already decide every
enumerated verb).

---

## T7 — docs + slice record: the guide, reconciliations, roadmap/stories/index, Mulch, folder move

**Goal.** The mechanism is documented as a guide, every doc describing the read-side surface is
reconciled, and the slice record closes.

**Deliverables.**
- **New guide `docs/guides/ACTION-ENRICHMENT.md`** (the mechanism guide — named to avoid a wikilink clash
  with the [[ACTION-ENRICHMENT]] index): the advice + `Enrichable` marker + `x-implements`/`_actions`
  codegen recipe (the one-sub-interface-+-two-schema-lines adoption), the cache feed (single + list
  write-through), the **three pinned semantics** (omit-on-failure, affordance-honesty,
  snapshot-not-verdict), the per-type verb sets (with the verified catalog/product `assign-tags`
  exclusion), and the documented caveats: enrichment is **affordance, not enforcement** (a present map is
  advisory; the gate decides); the kill-switch; the team Java-co-gated exclusion; `Membership` unenriched;
  the ungated-`getTeam` cache-miss degrade.
- Reconcile: [[ABAC-AUTHORIZATION]] (affordance as a **read-side** layer, distinct from ADR 0006's three
  enforcement layers — ADR 0006 body **not** edited), [[PARTIAL-EVALUATION-FILTERING]] (the list-path
  cache write-through), [[REST-API-DESIGN]] (the `_actions` envelope on resources/pages),
  `infra/README.md` + [[E2E-TESTING]] (the new matrix). The `Enrichable` Javadoc references the guide.
- [[USER-STORIES]]: flip the "show me only the buttons I can use" epic stories ✅. [[POC-ROADMAP]]: Phase
  6 shipped, next **B3** (route `B2 → 6.7 → 6 → B3 → 7`). Tick the [[ACTION-ENRICHMENT]] index status
  table through T7.
- Mulch: record the durable insights (the `ResponseBodyAdvice` + `Enrichable`/`x-implements` codegen
  shape; the per-type sub-interface as registry+allowlist; the P×V `allowAll` refold; the
  cache-relocation-to-core + list-path write-through feed; the omit-on-failure degrade contract; the
  affordance-honesty verb exclusion) — `git restore --staged .` **before** `ml sync`.
- `git mv docs/to-do/planning/ACTION-ENRICHMENT docs/to-do/implemented/ACTION-ENRICHMENT`, flip the index
  frontmatter to `status/done`, add the past-tense **Shipped** banner.

**Acceptance.** QA **D1–D3** (doc-presence checks). Frontmatter valid on every touched note; wikilinks
resolve; clean-room scan clean. **No push.**

**What NOT to touch.** ADR 0006 / ADR 0016 bodies (immutable). `CLAUDE.md` unless a build/run step
genuinely changed.

---

## Cross-cutting acceptance

- `./gradlew build` green throughout; **Testcontainers real Postgres** (never H2) for every IT; the e2e
  suite green end-to-end (new matrix + all existing); **`opa test` green** with the existing decision
  tests unmodified. *(Correction, 2026-06-17: not "zero Rego" — the slice **added** the `bulk` entrypoint
  to `catalog`/`product`/`team` rego, mirroring `category.rego`, with mirrored `opa test` cases; it adds no
  new decision, so the existing tests stay green, but OPA must reload on first pull. ADR 0016 §6.)*
- **`opa-abac-core` stays Spring-free** (the relocated `AbacResourceCache` + `Enrichable` carry no Spring
  import); **`opa-abac-spring-security` gains no dependency on `opa-abac-spring-data`** (the advice sees
  only core types + the marker).
- **Fail-closed — the three semantics, never traded:** (1) **omit `_actions` on any failure** (bulk error,
  cache miss, ancestor/role failure) — never a fabricated all-`false` map; present ⇒ complete real verdict;
  (2) **only fully-OPA-decided verbs enumerated** — the team Java-co-gated verbs excluded; (3) **the cache
  is a snapshot, never a verdict** — list survivors written, every verdict computed fresh, the gate still
  never reads the cache.
- **Additive:** `AbacResourceCache` relocates (pure move, every consumer recompiles, zero behavior change);
  `_actions` is an additive `readOnly` field on enriched schemas only; the kill-switch off-state is
  byte-identical; **enforcement (every `@OpaPreAuthorize`) and data-filtering decisions are untouched**;
  **zero `OpaClient` change, zero Rego change.**
- **Affordance ≠ enforcement** holds end-to-end: the advice never blocks a request; a present map is
  advisory; the real gate decides independently.
- Clean-room throughout. One focused commit per ticket, identity `Void3110 <void31102025@gmail.com>`,
  **no push**.

## Related

[[ACTION-ENRICHMENT]] (index) · [[00-DESIGN]] (mechanism + behavior matrix + proof obligations) ·
[[10-QA-TEST-CASES]] (the cases the acceptances reference) · ADR
[[0016-action-enrichment-affordance-metadata|0016]] (every pinned fork) · ADR
[[0013-attribute-rich-pre-authorization|0013]] (the cache this reuses) · ADR
[[0005-partial-eval-to-jpa-specification|0005]] (the `allowAll` batch primitive) · [[POC-ROADMAP]].
