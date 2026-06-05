---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring-data
---

# Hierarchical single-resource — decomposition (Slice 5.5-A)

> The ordered work list for [[HIERARCHY-SINGLE-RESOURCE]] (Phase 5.5-A of [[POC-ROADMAP]]). Seven tickets,
> one focused commit each. Design: [[00-DESIGN|00-DESIGN.md]]. QA: [[10-QA-TEST-CASES]]. Run via the
> [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]. Pinned by ADR [[0008-hierarchical-resource-authorization|0008]].
>
> **Packages.** Library: `dev.dmitriikonovalov.opaabac.{core,data,autoconfigure}`. Example:
> `dev.dmitriikonovalov.example.catalog.*`. **Critical path T1 → T2 → T3 → T4 → T5 → T6 → T7.** T1 is
> independently landable (pure core, unit tested). T2 depends on nothing but is exercised end-to-end only
> once T3 supplies a real `path`/parent linkage. T4 needs T1+T2. **Single-resource only — the list filter
> is Slice 5.5-B.**

---

## T1 — Core: `ParentRef` + `abacParent()` + `Resource.ancestors` (Spring-free, additive)

**Goal.** Give the framework a neutral way to name a resource's immediate parent and carry an ancestor
chain into OPA input — without any Spring/JPA in core.

**Deliverables.**
- `ParentRef` record `(String type, String id)` in `opa-abac-core` — both non-null (compact-ctor
  validation); a small neutral value type, no Spring/JPA imports.
- `AbacDataObject.abacParent()` — a new **default method** returning `Optional<ParentRef>` (default
  `Optional.empty()`). Purely additive: every existing impl compiles and behaves unchanged.
- `AbacContext.Resource` gains a `List<ParentRef> ancestors` field, serialized as
  `input.resource.ancestors`, **omitted when empty** (`@JsonInclude(NON_EMPTY)`), defensively copied. Add a
  back-compat constructor so existing 3-arg `Resource(type,id,attributes)` callers compile unchanged
  (ancestors → empty).

**Acceptance.** `./gradlew :opa-abac-core:test` green **and `./gradlew build` green** (no caller broke —
the `Resource` change is additive via the back-compat ctor). Unit tests: `ParentRef` rejects null
type/id; an `AbacContext` with ancestors serializes `input.resource.ancestors` as the expected ordered
`[{type,id}]` JSON; an `AbacContext` with **no** ancestors serializes **byte-for-byte as before** (no
`ancestors` key); `abacParent()` defaults to empty on a plain `AbacDataObject`.

**What NOT to touch.** No Spring/JPA in core. No `OpaClient` signature change (the resolver fills
`ancestors` before calling `allow`). No change to `role_definition`/`RoleDefinition`. No SQL.

---

## T2 — spring-data: `AncestorResolver` SPI + ltree & recursive-CTE impls (cycle + depth, fail-closed)

**Goal.** Resolve the **root-first, leaf-excluded** ancestor chain for a leaf `(type,id)`, fail-closed, in
two interchangeable ways.

**Deliverables.**
- `AncestorResolver` interface (`opa-abac-spring-data`): `List<ParentRef> ancestorsOf(String leafType,
  String leafId)`; documented fail-closed contract — a cycle / broken link / depth-bound breach / SQL error
  throws `AncestorResolutionException` (callers treat it as "no inheritance", never "allow"). A configurable
  `maxDepth`.
- `LtreeAncestorResolver` (default): reads the leaf row's denormalized `ltree path` and decodes it to the
  `(type,id)` chain in **one indexed query**; a `NULL`/malformed path → throw; depth = path length, bounded
  by `maxDepth`.
- `RecursiveCteAncestorResolver`: a recursive CTE up the parent linkage to the root; **cycle detection** via
  the CTE `CYCLE` clause (or a visited-set) → throw on cycle; **depth bound** via a depth column → throw on
  breach.

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green, incl. **Testcontainers ITs against real
Postgres** (never H2): both impls return the same root-first leaf-excluded chain for a seeded 3-level tree;
a seeded **cycle** → `AncestorResolutionException` (both impls); a tree **deeper than `maxDepth`** → throw;
a **broken** parent link / `NULL` path → throw. A unit test pins ordering (root-first) and leaf-exclusion.

**What NOT to touch.** No entity/Liquibase change yet (tests seed rows directly / via a test entity). No
OPA call here — this is pure resolution. No list-filter code (Slice 5.5-B).

---

## T3 — spring-data: `AbstractHierarchicalEntity` (ltree path + maintainer + atomic re-parent)

**Goal.** The opt-in persistent base that carries the `ltree path`, maintains it from the parent, and
re-parents a subtree atomically — so the ltree resolver has real lineage to read.

**Deliverables.**
- `AbstractHierarchicalEntity extends AbstractSecuredEntity` (`opa-abac-spring-data`): an `ltree path`
  column (mapped appropriately for Postgres), the `abstract Optional<ParentRef> abacParent()` declaration,
  and a **path-maintainer** that derives `path = parent.path || self-label` on insert/update (via the
  service/`mutate` seam or a JPA listener — match the existing write-centralization idiom).
- An **atomic `reparent(newParent)`** operation: updates the parent linkage **and** rewrites the `path` of
  the **entire moved subtree** in the **same transaction** (`UPDATE … SET path = <newPrefix> ||
  subpath(path, <oldDepth>) WHERE path <@ <oldSubtreeRoot>`), fail-closed if it can't complete.
- A reusable label convention (`<type>_<id>`) so a Product's path encodes the full `catalog → category →
  product` lineage (solving the missing-`catalogId` without a redundant FK).

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green incl. Testcontainers ITs: inserting a child
derives the correct `path`; **re-parenting a subtree rewrites every descendant's `path` atomically** (a
forced mid-rewrite failure leaves the tree **unchanged** — no half-rewrite); after a re-parent the
`LtreeAncestorResolver` returns the **new** chain. Non-hierarchical secured entities (still extending
`AbstractSecuredEntity`) are unaffected — no `path` column, no behavior change.

**What NOT to touch.** Don't load `path` onto `AbstractSecuredEntity` (keep it opt-in on the new base). No
example-app entity change yet (T6). No list filter.

---

## T4 — spring-data: the single-resource hierarchical check (`direct OR (walk_ok AND inherited)`)

**Goal.** Tie resolver → `ancestors` → OPA into one fail-closed single-resource decision, with
deny-overrides.

**Deliverables.**
- A small seam (e.g. `HierarchicalAuthorizer` in spring-data, mirroring how `AbacQueryService` ties the
  list path) that, given subject + action + a loaded leaf: calls `AncestorResolver.ancestorsOf(...)`,
  resolves the **role once on the governing root** (the chain's first element) via the existing
  `RoleDefinitionSupplier`, builds the `AbacContext` with `resource.ancestors` set, and calls
  `opaClient.allow(...)`.
- **Fail-closed wiring:** an `AncestorResolutionException` (or no inheritable config) → supply **no
  ancestors** → the decision can only come from the **direct** leaf grant
  (`final_allow = direct OR (walk_ok AND inherited)`), degrading to today's behavior. Deny-overrides is the
  policy's job (T6) but the seam must pass the leaf's tags so a leaf deny can fire.

**Acceptance.** `./gradlew :opa-abac-spring-data:test` green: with a stub `OpaClient` + `RoleDefinitionSupplier`
and a stub/real resolver — a Catalog grant authorizes a deep Product (ancestors carried, role resolved on
the root); a resolver **failure** falls back to the direct grant only (never wider); an unresolved role →
deny; an `ArgumentCaptor` asserts `input.resource.ancestors` is root-first/leaf-excluded and the role was
resolved on the **root**.

**What NOT to touch.** Don't change `@OpaPreAuthorize` / the type-level manager. No list filter. Don't
resolve a role per-ancestor (root-only — per-node grants are Phase 8).

---

## T5 — starter: wire the resolver SPI + inheritance config (default-off) + `maxDepth`

**Goal.** Auto-configure the hierarchy beans, conditional + overridable, **default-off** for inheritance.

**Deliverables.**
- Beans (`@ConditionalOnMissingBean`, security/data ones `@ConditionalOnClass`): `AncestorResolver`
  (default `LtreeAncestorResolver`; the CTE impl selectable by property), the `HierarchicalAuthorizer` seam.
- `OpaAbacProperties` additions: a `hierarchy` group — `enabled` (default **false** — opt-in), `resolver`
  (`ltree`|`cte`, default `ltree`), `maxDepth` (default e.g. 32), and the **structural inheritance
  declaration** (`inheritable: {childType: [ancestorType…]}`, default empty). Regenerate
  `spring-configuration-metadata.json`.

**Acceptance.** `./gradlew :opa-abac-spring-boot-starter:test` green: `ApplicationContextRunner` — beans
present iff `hierarchy.enabled` + classpath; the resolver impl switches by property; an app-supplied
`AncestorResolver`/seam **overrides** the default; properties bind; **default-off** verified (no inheritance
config ⇒ no widening).

**What NOT to touch.** Don't auto-enable hierarchy (default-off). No change to the shipped partial-eval
beans/`partialEval` properties. No `SecurityFilterChain` ownership.

---

## T6 — example + infra: catalog adoption + rego inheritance clause + Liquibase ltree migration

**Goal.** Make the catalog app hierarchical end to end and express inheritance in policy.

**Deliverables.**
- `CategoryEntity`/`ProductEntity` extend `AbstractHierarchicalEntity`; `abacParent()` returns the correct
  one hop (root Category → `("catalog", catalogId)`; nested Category → `("category", parentId)`; Product →
  `("category", categoryId)`).
- Liquibase changelog: add the `ltree path` column (+ the Postgres `ltree` extension if needed) + a GIN
  index + **backfill** existing rows' paths; `ddl-auto: validate` clean.
- Replace `CategoryAuthorizer`/`CategoryListAuthorizer`'s hard-coded `("catalog", catalogId)` hop with the
  library `HierarchicalAuthorizer` (single-resource path); add a deep `GET …/products/{id}` that exercises a
  3-level chain; expose a Category **reparent** operation for the e2e.
- `category.rego`/`product.rego`: the **inheritance clause** (`inherited_grant` over
  `input.resource.ancestors`, gated by the opt-in `inheritable[childType][ancestorType]` in OPA data) +
  **deny-overrides** (a leaf deny wins). `opa test` covers: direct grant; inherited grant via an ancestor;
  **opt-in off ⇒ no inheritance**; deny-overrides; no-ancestors ⇒ direct-only.

**Acceptance.** `./gradlew build` green (incl. `ddl-auto: validate` boot + the example ITs); `opa test
infra/opa/policies/` green incl. the new inheritance/deny cases. A manual `opa eval` probe confirms an
ancestor grant satisfies a descendant action.

**What NOT to touch.** No list-endpoint hierarchy widening (Slice 5.5-B — list endpoints keep today's
Phase-5 tag-only filter). No `RoleDefinition` change. Keep the residual model untouched.

---

## T7 — e2e (incl. the mandatory re-parent test) + docs + roadmap/Mulch

**Goal.** Prove the decisive contrasts through the gateway and finalize the slice record.

**Deliverables.**
- Postman/newman matrix (`scripts/postman/`): (a) a subject with a **Catalog** grant reads a **Product 3
  levels down** (200); (b) an explicit **deny** on one Category carves it out (403) while siblings stay
  readable (deny-overrides); (c) **a subtree re-parent flips a decision** — move Category 7 under a Catalog
  the subject can't see → the Product becomes **denied**; (d) a broken/too-deep chain → **direct grant
  only**, never wider. Mint tokens in-network; restart OPA after the rego edit.
- Docs: write `docs/guides/HIERARCHICAL-AUTHORIZATION.md` (the walk, the SPI + ltree/CTE trade-off, the
  opt-in + deny-overrides, the fail-closed posture, the adoption recipe); reconcile `infra/README.md` +
  `docs/guides/E2E-TESTING.md`; tick the [[HIERARCHY-SINGLE-RESOURCE]] status table; update [[POC-ROADMAP]]
  (5.5-A done); move the folder to `docs/to-do/implemented/` with a Shipped banner.
- Mulch: record the durable insights (the ltree-vs-CTE resolver SPI; the atomic re-parent invalidation; the
  `direct OR (walk_ok AND inherited)` fail-closed walk; the opt-in-default-off inheritance; chain-as-input).
  **`git restore --staged .` before `ml sync`** so the sync commit touches `.mulch/` only.

**Acceptance.** Rig up → the newman matrix green incl. **the re-parent test**; `bash -n` clean; JSON valid;
docs/roadmap/Mulch updated; **clean-room scan clean**. **No push.**

---

## Cross-cutting acceptance

- `./gradlew build` green throughout; `opa test` green; **Testcontainers real Postgres** (never H2) for all
  ITs, incl. the cycle/depth/broken-chain and the **atomic re-parent** cases.
- **`opa-abac-core` stays Spring-free** (T1's `ParentRef`/`abacParent()`/`ancestors` carry no Spring/JPA).
- **Fail-closed everywhere:** a failed/cyclic/too-deep walk → direct grant only (never wider, never strips a
  direct grant); inheritance **opt-in, default-off**; deny-overrides narrows.
- **Additive:** `abacParent()` is a default; `ancestors` omitted when empty; `AbstractHierarchicalEntity` is
  opt-in; `RoleDefinition` unchanged; the Phase-5 residual model/operator set untouched.
- **Single-resource only** — no list-endpoint widening (that's 5.5-B). Clean-room throughout. One focused
  commit per ticket, identity `Void3110 <void31102025@gmail.com>`, **no push**.
