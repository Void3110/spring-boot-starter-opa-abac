---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# Resource resolution — decomposition (Phase 5.97)

> The ordered work list for [[RESOURCE-RESOLUTION]] (Phase 5.97 of [[POC-ROADMAP]]). Seven tickets, one
> focused commit each. Design: [[00-DESIGN]]. QA: [[10-QA-TEST-CASES]]. Run via the
> [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]. Pinned by ADR [[0013-attribute-rich-pre-authorization|0013]];
> the four retro-audit fold-ins ([[RESOURCE-RESOLUTION]] §"Inputs from the retro-audit") land in T2/T3
> (409 advice), T5 (`tags_satisfied` siblings), and the QA baseline (supplier outage, TOCTOU cells).
>
> **Packages.** Library: `dev.dmitriikonovalov.opaabac.{core,security,autoconfigure}` (+ one line in
> `…opaabac.data.model`). Example: `dev.dmitriikonovalov.example.catalog.*`. **user-mgmt: zero changes**
> (the live opt-in coexistence proof).

## Critical path

```
T1 ──► T2 ──► T3 ──► T4 ──► T6 ──► T7
 └────────────────► T5 ──────┘
```

T1 → T2 → T3 → T4 are strictly sequential (each wires the previous ticket's seams). **T5 (the policy
conjunct) is independent of T2–T4** — it can land any time after T1, but must land **before T6** (the
e2e matrix exercises tag-gated product/catalog writes). **T1+T2+T3 are the independently-landable
subset**: the complete library mechanism, opt-in and dormant until an app registers a resolver bean.
T7 closes the record.

## Two pinned contract semantics (so the run never stops to ask)

1. **Missing resource behind an annotated `resourceId` → `403`, not `404`.** ADR 0013 §3 is literal:
   resolver empty/throws → DENY. So once the catalog registers a resolver, an id'd, annotated endpoint
   given a nonexistent id answers `403 ACCESS_DENIED` (the gate denies; the handler never runs) — the
   pre-5.97 `404` on those paths becomes `403` (anti-enumeration posture). The handler's **URL-scope
   rule is unchanged**: an *existing* resource under the wrong path scope (e.g. a category under the
   wrong catalog) is still the handler's `404` (`findByIdAndCatalogId` semantics). Any IT/e2e cell that
   pinned `404` for a *missing* id through an *annotated* endpoint is updated to `403` and the change
   is listed in that ticket's STATUS note.
2. **`tags_satisfied` lands as the conjunct, not as a documented scope-out.** Retro-audit fold-in #3
   offered "the conjunct or a documented category-only scope"; this package picks the **conjunct**
   (port `category.rego`'s block to `product.rego` + `catalog.rego`, T5) — the audit's security-first
   disposition; the attribute-rich gate is only as good as the policies it feeds.

---

## T1 — Core: the split SPI + version types (Spring-free, additive)

**Goal.** Give the framework the neutral contracts for instance resolution, ancestor supply, and
version binding — without any Spring in core.

**Deliverables.**
- `opa-abac-core`, package `dev.dmitriikonovalov.opaabac.core`:
  - `AbacResourceResolver` — `Optional<AbacDataObject> resolve(String resourceType, String resourceId)`;
    Javadoc pins the split failure semantics (empty/throw → the caller DENIES) and that the app
    implements **one** dispatching bean. *(Wired in T2/T3; app impl in T4.)*
  - `AncestorChainSupplier` — `List<ParentRef> ancestorsOf(String resourceType, String resourceId)`;
    Javadoc pins root-first/leaf-excluded order (the 5.5 contract) and throw-on-failure → the caller
    collapses to the empty chain. *(Bound by the starter in T3 — apps never implement it directly.)*
  - `Versioned` — `Integer getVersion()`.
  - `VersionConflictException extends RuntimeException` (carries type/id + expected/actual versions in
    the message; no internal details beyond that).
  - `VersionGuard` — `static void requireUnchanged(Versioned snapshot, Versioned current)`: throws
    `VersionConflictException` on drift; a `null` version on either side **throws too** (an unguardable
    resource must fail loud when guarding was requested, never silently pass). *(Adopted in T4.)*
- `opa-abac-spring-data`: `BaseModel<ID> extends Versioned` — the **only** spring-data diff (the
  `getVersion()` method already exists on it; this is a pure hierarchy statement).

**Acceptance.** QA **U1–U4**. `./gradlew :opa-abac-core:test` green and **`./gradlew build` green**
(the `BaseModel` change is provably additive — nothing else recompiles differently). Unit tests:
`VersionGuard` passes on equal versions; throws on drift; throws on `null` version; `ParentRef` /
`AbacDataObject` untouched (no test edits).

**What NOT to touch.** `opa-abac-core` stays Spring-free (prove with the import set). No
`OpaClient` / `AbacContext` / `RoleDefinitionSupplier` signature change. No manager change yet (T2).
No new version field anywhere — the JPA `@Version` is the one version (ADR 0013 §5).

---

## T2 — spring-security: the manager's resolution flow + the request-scoped cache + the core 409 mapping

**Goal.** With resolution collaborators present, `OpaPreAuthorizeAuthorizationManager` makes the full
per-instance decision (attributes + ancestors + governing-root role) and write-through-caches the
authorized instance; without them, the gate is byte-identical to today.

**Deliverables.** Package `dev.dmitriikonovalov.opaabac.security`:
- `AbacResourceCache` — `<T> Optional<T> get(String resourceType, String resourceId, Class<T> as)` +
  `void put(String resourceType, String resourceId, Object resource)`; Javadoc: write-through on allow,
  request-bounded, **never consulted by decisions**. *(Read by the catalog handlers in T4 and by
  Phase-6 enrichment.)*
- `RequestAttributesResourceCache implements AbacResourceCache` — storage via
  `RequestContextHolder.getRequestAttributes()` (request scope, no scope proxying); **no request
  context → `get` returns empty, `put` is a no-op** (never throws).
- `ResourceResolutionSupport` — a small composition type carrying
  `(AbacResourceResolver, AncestorChainSupplier /*nullable*/, AbacResourceCache)`. *(Constructed by the
  starter in T3.)*
- `OpaPreAuthorizeAuthorizationManager` — a **new constructor overload** taking
  `ResourceResolutionSupport` (the existing constructors delegate with `null` support and keep today's
  behavior). For the **declared-`resourceId` branch only**, when support is present:
  1. `resolver.resolve(type, id)` — empty or throw → **deny** (no OPA call needed; never an
     attribute-less context).
  2. `chainSupplier.ancestorsOf(type, id)` — throw → **chain = `List.of()`** (direct-only); supplier
     `null` → empty chain.
  3. Role looked up **once on the governing root**: `ancestors.isEmpty() ? leaf : ancestors.get(0)` —
     exactly `HierarchicalAuthorizer`'s rule (quote it in the Javadoc).
  4. `AbacContext` carries the instance's `abacAttributes()` **and** the ancestors → `opaClient.allow`.
  5. On **allow**: `cache.put(type, id, instance)`. The `resource()`-SpEL branch also `put`s its
     instance on allow (its decision inputs are unchanged). Deny puts nothing. The gate **never reads**
     the cache.
  - Support absent (or the T3 kill-switch left it unwired) → all five steps skipped; the built context
    is **byte-identical** to today's. Type-level checks (no `resourceId`) never engage the resolver.
- `AbstractProblemAdvice` — a new `@ExceptionHandler(VersionConflictException.class)` →
  `LibraryErrorCode.STATE_CONFLICT` (`409` problem+json). *(Reached end-to-end by T4's IT — the
  non-happy path through the new seam.)*

**Acceptance.** QA **U5–U13**. `./gradlew :opa-abac-spring-security:test` green. The headline unit
case: with support absent, the serialized OPA input for an id'd check is **byte-for-byte identical**
to the pre-5.97 manager's (U5). Mockito-style manager tests follow the existing
`OpaPreAuthorizeAuthorizationManagerTest` pattern.

**What NOT to touch.** The existing constructors/behavior (additive overload only — if any existing
test stub must widen, it lands in this commit, named in STATUS). The `resource()` branch's decision
inputs and the type-level branch (unchanged but for the documented `put`). No starter wiring yet (T3).
No `AbacQueryService` / list-path code. `opa-abac-spring-security` does **not** gain a dependency on
`opa-abac-spring-data` (the manager sees only core types — that boundary is the reason
`AncestorChainSupplier` exists).

---

## T3 — starter: auto-config composition + kill-switch + the persistence 409 advice

**Goal.** Compose what the modules can't see of each other — resolver + ancestor binding + cache into
the manager, gated by bean presence and the kill-switch — and ship the shared persistence-conflict
mapping.

**Deliverables.** Package `dev.dmitriikonovalov.opaabac.autoconfigure`:
- `OpaAbacProperties`: a `resourceResolution` group with `enabled` (default **true**) — prefix
  `opa.abac.resource-resolution`. Regenerate `spring-configuration-metadata.json`.
- In `OpaAbacAutoConfiguration`: a `ResourceResolutionAutoConfiguration` nested class —
  `@ConditionalOnBean(AbacResourceResolver.class)` +
  `@ConditionalOnProperty(prefix = "opa.abac.resource-resolution", name = "enabled",
  havingValue = "true", matchIfMissing = true)`:
  - `AbacResourceCache` bean (default `RequestAttributesResourceCache`, `@ConditionalOnMissingBean`).
  - `ResourceResolutionSupport` bean: binds `AncestorChainSupplier` to the 5.5
    `AncestorResolver::ancestorsOf` when that bean exists (`ObjectProvider` — hierarchy off → `null`
    supplier → empty chain), and composes resolver + supplier + cache.
  - The manager wiring passes the support to the T2 constructor overload; with the condition unmet,
    the existing manager wiring is **byte-identical** (the kill-switch/rollback path of ADR 0013 §6).
- `PersistenceConflictProblemAdvice` (`@RestControllerAdvice`, auto-registered,
  `@ConditionalOnClass(org.springframework.dao.OptimisticLockingFailureException.class)` +
  `@ConditionalOnWebApplication` + `@ConditionalOnMissingBean`): maps
  `OptimisticLockingFailureException` and `DataIntegrityViolationException` →
  `LibraryErrorCode.STATE_CONFLICT` (`409` problem+json, same shape as `AbstractProblemAdvice`
  produces). **Retro-audit fold-in #1** — races and FK conflicts stop answering `500`; this also moves
  the audit's "delete-an-in-use-role answers 500" follow-up to `409` for both services with zero
  per-service code. *(Non-happy path reached live by T4's IT and the user-mgmt suite in T6.)*

**Acceptance.** QA **U14–U18**. `./gradlew :opa-abac-spring-boot-starter:test` green —
`ApplicationContextRunner`: no resolver bean → no support bean, manager baseline; resolver bean →
support wired (with and without an `AncestorResolver` present); `opa.abac.resource-resolution.enabled=false`
→ no support despite the bean; advice present iff the dao class is on the classpath; metadata contains
the new property.

**What NOT to touch.** The existing `SecurityAutoConfiguration` / `DataFilteringAutoConfiguration` /
`HierarchyAutoConfiguration` beans and their conditions (additive nested class only). `partial-eval`
and `hierarchy` property groups unchanged. No `SecurityFilterChain` ownership.

---

## T4 — catalog adoption: the resolver bean, `getCategory` to the gate, version guards, cache reuse + ITs

**Goal.** The example proves the mechanism: one resolver bean, the layer-3 tag check deleted, reads
reuse the authorized snapshot, mutations guard the snapshot's version — pinned by ITs against real
Postgres.

**Deliverables.** Package `dev.dmitriikonovalov.example.catalog`:
- `config/CatalogResourceResolver implements AbacResourceResolver` (~15 lines): dispatch
  `"catalog"`/`"category"`/`"product"` → `CatalogRepository`/`CategoryRepository`/`ProductRepository`
  `findById`; unknown type → `Optional.empty()`. Registered as a bean.
- `web/CategoryController.getCategory`: **gains**
  `@OpaPreAuthorize(action = "category:read", resourceType = "'category'", resourceId = "#categoryId")`
  (the annotation this endpoint never had); drops the `categoryAuthorizer.require("read", entity)`
  call; reads the entity from `AbacResourceCache.get("category", categoryId.toString(),
  CategoryEntity.class)` with a repository fallback when the cache is empty (resolution off / no web
  context); **keeps the URL-scope rule in the handler** — cached instance's `catalogId` ≠ path
  `catalogId` → `404` (the resolver loads by id alone and must not absorb routing semantics).
- **`config/CategoryAuthorizer` deleted** (single production call site). Any test referencing it is
  migrated/deleted **in this same commit** (search `src/test` — this is the ticket's build-breaker).
  `HierarchicalAuthorizer` (library) **stays** — the programmatic alternative.
- Read-handler cache reuse: `getCatalog`, `getProduct` follow the same cache-then-repo pattern.
- Mutating handlers adopt the guard — `updateCategory`, `deleteCategory`, `updateCatalog`,
  `deleteCatalog`, `updateProduct`, `deleteProduct`: load **fresh** in-transaction (as today), then if
  the cache holds the gate's snapshot, `VersionGuard.requireUnchanged(snapshot, fresh)` **before any
  write**; no snapshot (resolution off) → today's behavior (the documented degrade, never silent —
  the guide's caveat, T7). **Never persist the snapshot.** The guard is the gate-binding check; the
  JPA `@Version` + the audited `reparentCategory` locks remain the mutation-safety mechanism — do not
  reorder or weaken either (`CONCURRENCY-AND-LOCKING.md` Rules 1–2).
- Test support: a **programmable, context-aware `OpaClient` test stub** for the new
  `ResourceResolutionGateIT` (decides from `input.resource.attributes`/`ancestors` — proving the gate
  decided on *resolved* state, the in-process-stub convention). The existing CRUD ITs
  (`CatalogCrudIT`, hierarchy ITs) run with `opa.abac.resource-resolution.enabled=false` in their
  profile — they test CRUD, not authorization, and double as the **kill-switch off-state proof**
  (byte-identical baseline; pre-5.97 status codes preserved).

**Acceptance.** QA **I1–I7**. `./gradlew build` green (all modules + example ITs). Headline cells:
tag-mismatch write denied **at the gate** (no handler execution), tag-match write allowed (I1/I2); the
deterministic version-guard race → `409` problem+json `errorCode=STATE_CONFLICT` (I4); a missing id
through an annotated endpoint → `403` (I6, pinned semantic #1); `getCategory` parity incl. the
scope-mismatch `404` (I3).

**What NOT to touch.** `CategoryListAuthorizer`, `AbacQueryService` and all four `findAuthorized`
paths, pagination — untouched (list-path cache population is Phase 6's). Every **other** annotation
byte-identical (only `getCategory` gains one). The OpenAPI specs (no shape change). user-mgmt — zero
bytes. No Liquibase change (the `@Version` column exists; `ddl-auto: validate` boot is the proof).

---

## T5 — policies: the `tags_satisfied` conjunct for `product.rego` + `catalog.rego` (retro-audit fold-in #3)

**Goal.** Close the audited sibling hole: a role's `required_tags` must gate product and catalog
writes exactly as it gates category writes — otherwise the attribute-rich gate feeds rules that
ignore the attributes.

**Deliverables.** In `infra/opa/policies/`:
- Port `category.rego`'s tag-matching block to `product.rego` and `catalog.rego` **mirroring its
  structure exactly** (`has_required_tags`, `key_satisfied`, the three `tags_satisfied` clauses —
  ANY_OF / ALL_OF / vacuous back-compat — and the same conjunct placement on the role-definition
  grant path, including the 5.5 inherited-grant path where the file has one). No novel policy design —
  this is a sibling sweep, `category.rego` is the template.
- `product_test.rego` / `catalog_test.rego`: the new cells mirroring `category_test.rego`'s tag cases
  — ANY_OF match allows; mismatch denies; `ALL_OF` both-keys; **missing resource attributes + a
  tag-requiring role → deny** (fail-closed); a role with no `required_tags` is **unaffected**
  (vacuous); the realm-fallback path unaffected.

**Acceptance.** QA **P1–P5**. `opa test infra/opa/policies/` green — **including every pre-existing
case unmodified** (the vacuous clause keeps tag-free roles byte-identical; if an existing test seeds a
tag-requiring role against an attribute-less product/catalog input, that test is asserting the hole —
flip it to deny and say so in STATUS).

**What NOT to touch.** `category.rego` (already correct — the template, not a target). The realm
fallback clauses, the list-gate clause, `inherited_grant` logic, `filter` entrypoints — structure
unchanged; the conjunct only **narrows** the role-definition grant path. `team.rego`/user-mgmt
policies untouched. Zero Java in this ticket.

---

## T6 — e2e: the resource-resolution matrix (fixture catalog `8888…`) + whole-suite coexistence

**Goal.** Prove the §3 behavior matrix through APISIX — the headline flip, the closed fallback hole,
the intended narrowing, the non-member unchanged — and that everything already shipped still holds.

**Deliverables.** In `scripts/postman/`:
- `resource-resolution-matrix.postman_collection.json` + `run-resource-resolution-matrix.sh`
  (mirroring `run-tag-matrix.sh`'s fixture seeding through the user-service bootstrap). Dedicated
  namespaced fixture set under catalog **`88888888-8888-8888-8888-888888888888`** (registered in the
  fixture-id registry; shared fixtures must not grow): two categories (tags `region=emea` /
  `region=apac`) each with a product; one team bound to the `8888…` catalog target; a tag-gated write
  role (`required_tags: {region: [emea]}`, ANY_OF) and a read-only role.
- The cells (each asserts the **decision**, not just a status shape) — QA **E1–E7**:
  - **E1 the headline flip**: member, tag-gated write role, `viewer` realm role, PUT the `emea`
    category → **200** (pre-5.97: 403 — story C4's first half).
  - **E2 the hole closes**: member, same role, `editor` realm role, PUT the `apac` category →
    **403** (pre-5.97: 200 via the tag-blind realm fallback — C4's second half).
  - **E3 the narrowing**: member, read-only team role, `editor` realm role, PUT → **403**
    (role-def present → fallback disabled).
  - **E4 non-member unchanged**: no membership, `editor` realm role, PUT → the fallback still
    decides (200) — byte-identical cell.
  - **E5 parity**: root-granted member GETs a nested category → 200 **via the gate** (inherited
    grant survives the move from layer 3).
  - **E6 the product sibling**: the tag-gated role against the `apac` product write → 403 (T5's
    conjunct live end-to-end).
- **The whole existing suite green** (every `run-*.sh` matrix incl. team/tag/filter/hierarchy/
  pagination + `catalog-e2e`): user-mgmt untouched is *proven*, not assumed. A pre-existing cell that
  pinned `404` for a missing id through an annotated endpoint legitimately flips to `403` (pinned
  semantic #1) — update it and list every such cell in STATUS.

**Acceptance.** Rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`;
`./deploy.sh build` to force new images; mint tokens **in-network**; restart OPA after the T5 rego
edits) → the new matrix green **and** every existing matrix green. `bash -n` clean; collection JSON
valid.

**What NOT to touch.** Existing matrices' pinned row counts and decisions (beyond the documented
`404→403` class). Shared fixtures. The gateway config.

---

## T7 — docs + slice record: the guide, the reconciliations, roadmap/stories/index, Mulch, folder move

**Goal.** The mechanism is documented as a guide, every doc that described the old boundary is
reconciled, and the slice record closes.

**Deliverables.**
- **New guide `docs/guides/ATTRIBUTE-RICH-PRE-AUTHORIZATION.md`** (named to avoid a wikilink clash
  with the [[RESOURCE-RESOLUTION]] index note): the mechanism (SPI split, governing-root role, the
  cache, version binding), the split fail-closed semantics, the adoption recipe (one bean + zero
  annotation changes), and the **documented caveats**: the kill-switch restores baseline semantics
  (attribute-keyed deny rules only enforced while resolution is on); unversioned resources can't be
  guarded (the window stays load-then-check, never silent); **the supplier-outage scope-out** —
  a `RoleDefinitionSupplier` outage is indistinguishable from an authoritative no-role and the realm
  fallback then decides (retro-audit fold-in #2: explicitly scoped out of 5.97, tracked as a
  follow-up); the missing-id `403` posture (pinned semantic #1).
- Reconcile: [[TAG-BASED-AUTHORIZATION]] (tag rules are now gate-decidable — the flip),
  [[HIERARCHICAL-AUTHORIZATION]] (gate vs programmatic `HierarchicalAuthorizer`),
  [[ABAC-AUTHORIZATION]] (the redrawn layer-2/3 boundary, per ADR 0013 — ADR 0006 itself is **not
  edited**), `OpaPreAuthorize`'s Javadoc ("a later phase" → this phase, T2 may have done it),
  `infra/README.md` + `docs/guides/E2E-TESTING.md` (the new matrix).
- [[USER-STORIES]]: flip **C4** ✅. [[POC-ROADMAP]]: 5.97 shipped, next 6.5. Tick the
  [[RESOURCE-RESOLUTION]] index status table through T7.
- Mulch: record the durable insights (the split-SPI + starter-composition shape; deny-vs-collapse
  split semantics; the request-attributes write-through cache; version binding via the one `@Version`
  + the 409 contract; the missing-id 403 posture) — `git restore --staged .` **before** `ml sync`.
- `git mv docs/to-do/planning/RESOURCE-RESOLUTION docs/to-do/implemented/RESOURCE-RESOLUTION`, flip
  the index frontmatter to `status/done`, add the past-tense **Shipped** banner.

**Acceptance.** QA **D1–D3** (doc-presence checks). Frontmatter valid on every touched note; wikilinks
resolve; clean-room scan clean. **No push.**

**What NOT to touch.** ADR 0006 / ADR 0013 bodies (immutable). `CLAUDE.md` unless a build/run step
genuinely changed.

---

## Cross-cutting acceptance

- `./gradlew build` green throughout; `opa test infra/opa/policies/` green; **Testcontainers real
  Postgres** (never H2) for every IT; the e2e suite green end-to-end (new matrix + all existing).
- **`opa-abac-core` stays Spring-free** (T1's four types carry no Spring import).
- **Fail-closed, both semantics, never confused:** instance resolution empty/throws → **deny**;
  ancestor failure → **collapse to direct-only** (never strips a direct grant, never widens); no
  resolver bean / kill-switch off → **byte-identical baseline** (proven by serialization equality, the
  CRUD-IT profile, and the unchanged user-mgmt suite); version drift → **409**, never a silent
  overwrite; the cache is never an input to a decision.
- **Additive:** new core types only; the manager change is a constructor overload; `BaseModel extends
  Versioned` is the lone spring-data line; `AbacQueryService` + the four `findAuthorized` paths,
  pagination, `CategoryListAuthorizer`, every existing annotation (except `getCategory`'s gain), the
  OpenAPI specs, and **user-mgmt end-to-end** are untouched.
- **Zero Rego required by the mechanism** (ADR 0013 additivity holds); the only policy diff is T5's
  audit-mandated `tags_satisfied` sibling conjunct — a pure narrowing with vacuous back-compat.
- Clean-room throughout. One focused commit per ticket, identity `Void3110 <void31102025@gmail.com>`,
  **no push**.

## Related

[[RESOURCE-RESOLUTION]] (index) · [[00-DESIGN]] (mechanism + behavior matrix) ·
[[10-QA-TEST-CASES]] (the cases the acceptances reference) · ADR
[[0013-attribute-rich-pre-authorization|0013]] (every pinned fork) ·
[[RETRO-AUDIT-2026-06-12]] (the four fold-ins) · [[POC-ROADMAP]].
