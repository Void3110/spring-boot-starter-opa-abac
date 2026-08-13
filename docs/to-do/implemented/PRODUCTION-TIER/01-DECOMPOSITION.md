---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# PRODUCTION-TIER — decomposition

> T1…T6, in order. Each ticket is one focused commit's worth of work. The design is [[00-DESIGN]];
> the contracts are [[0030-step-up-decision-contract|ADR 0030]] §1–4 and
> [[0032-root-attribute-enrichment-input-contract|ADR 0032]] (as amended §Population). Seam claims
> below were verified against source on 2026-08-07 (the decompose seam sweep); each ticket notes how.

## Critical path

```
T1 ──► T2 ─────────┐
 └───► T4 ─────────┼──► T5 ──► T6
       T3 ─────────┘
```
*(dependency graph — the execution order is still strictly T1→T6; T4 depends on T1 only)*

- **Sequential:** T1 → T2 (the flag must exist and travel in `TagDefinitionView` before the catalog
  side can enforce it). T4 after T1 only because its opa fixtures reference the seeded `env` values —
  it does not depend on T2/T3 code.
- **Parallel:** T3 (library) is independent of T1/T2/T4 and may land any time before T5.
- **Independently landable subset:** **T1–T4 = part 0** (see `00-DESIGN` §Execution parts). After
  T4 the corpus provably closes supervised production/unproven contents while nothing user-visible
  changes (the safe closed-by-absence intermediate state: the role is widened, the denies are live,
  and no input carries `root_attributes` yet, so supervised contents stay closed exactly as in
  slice A).
- **Part 1:** T5 → T6 (enrichment proof below the rig, then the rig proof).

---

## T1 — user-service: the `operatorManaged` dictionary flag, the `env` seed, and the wire models

**Goal.** The tag dictionary can mark a key operator-managed, `env` exists as that key, and both
wire surfaces (the public API model and the internal projection source) carry the flag.

**Deliverables.**
- `TagDefinition` entity (`…usermgmt.domain.TagDefinition`, table `tag_definition`): new boolean
  column `operator_managed`, default `false`, mapped as `operatorManaged` — alongside the existing
  `is_system` mapping (`:70`), whose meaning ("definition immutable through the API") it does
  **not** replace. *(Seam verified: entity fields + table read from source.)*
- Liquibase changeset **`0008-operator-managed-tag.yaml`** (next after `0007-create-reporting-edge`;
  appended as an `- include:` in `db.changelog-master.yaml`): the `operator_managed` column
  (`NOT NULL DEFAULT false`) **and** the `env` seed row — id `00000000-0000-0000-0000-0000000000a3`,
  key `env`, scope `GLOBAL`, `ENUM`/`SINGLE`, allowed values `["production","staging","dev"]`,
  `is_system: true`, `operator_managed: true` — mirroring the `0004` seed shape. *(Seam verified:
  `0004-seed-tag-definitions.yaml` read; master's last include is `0007`.)*
- `user-mgmt-api.yaml`: the `TagDefinition` response schema gains `operatorManaged` (boolean,
  documented: "values under this key are not writable through any public API; only the operator
  path sets them"). **Request schemas gain nothing** — the flag is not client-authorable, and
  GLOBAL definitions have no public create path at all (only `createTeamTagDefinition` exists).
- `UserMgmtMapper.toDto` (`:95-112`) sets the new field. The internal projection source
  (`InternalResolveController.tagDefinitionsForResource`, `/internal/tag-definitions`) then carries
  it with no further change — it maps through the same `toDto`. *(Seam verified: producer path
  read.)*
- **Documentation delta (this commit):** a short *Operator-managed keys* subsection in
  `docs/guides/TAG-BASED-AUTHORIZATION.md` — the flag's meaning, the `is_system` distinction, the
  no-public-write-path rule, and the ADR 0030 §3 trust dependency (untagged ⇒ non-production only
  while this holds).

**Acceptance.** **U1**, **U2** · **I1**, **I2**. Non-happy path: I2 asserts the flag round-trips for `env` **and**
that pre-existing keys report `false` (the additive default).

**NOT to touch.** No enforcement here (T2's). No catalog-service files. The `sensitivity`/`region`
seeds and every team key stay `operator_managed=false`. `ddl-auto: validate` must boot clean — the
changeset and the entity move in the same commit.

---

## T2 — catalog-service: operator-managed write rejection + the operator endpoint

**Goal.** No public write path can change an operator-managed key's value on any resource, the
rejection is distinguishable on the wire, and the operator has an in-network path that can.

**Deliverables.**
- `TagDefinitionView` (`…catalog.config.TagDefinitionView`) gains an `operatorManaged` component
  declared as the **`Boolean` wrapper, normalized in the compact constructor**
  (`operatorManaged = operatorManaged != null && operatorManaged` — the record's existing
  `allowedValues` null-normalizing idiom). **Not a primitive `boolean`**: Jackson 3 *throws* on a
  missing primitive record component (verified empirically against the shipped jars), so the
  wrapper+normalize is what makes a missing field — an old user-service — read as `false`,
  back-compat by construction. *(Seam verified: record read; it currently carries
  `key/valueType/cardinality/allowedValues/valuePattern` only.)*
- `TagAssignmentService.validateAndBuild` gains the **current tag map** parameter
  (`Map<String, Object> currentTags`; the existing 3-arg signature delegates with an empty map so
  create paths stay source-compatible). **The rejection is delta-based:** for every key whose
  definition is operator-managed, the submitted map's presence-and-value must equal the current
  map's — an **assign** (absent → present), **re-value** (value change), or **strip** (present →
  absent) throws the new `TagOperatorManagedException`; an **echo** (unchanged value, or absent on
  both sides) passes, because writes are full-map-replace and an echo-rejection would freeze every
  tag edit on a tagged resource. **The empty/null-submitted fast path (`:51-53`) becomes
  conditional: it may only skip the definitions fetch when `currentTags` is ALSO empty** —
  submitting `null`/`{}` over a resource that currently carries an operator-managed key **is a
  strip** and must throw; without this, strip-via-empty-map bypasses the whole rejection.
  *(Seam verified: `validateAndBuild(resourceType, resourceId, submittedTags)` returns the full
  `ResourceTags` to persist — it never sees current tags today, and returns `ResourceTags.empty()`
  before any check when the submitted map is empty.)*
- **Every call site passes the loaded entity's current tags** — the update path in
  `CatalogController` (`:128`; create rejects tags outright before assignment) and the
  create+update paths in `CategoryController` (`:97`, `:160`), `ProductController` (`:92`,
  `:154`); enumerate by grep at implementation and update them **in this commit** (the widened
  signature is the build-breaker; the delegating overload contains it).
- `CatalogErrorCode` gains its **first constant**: `TAG_OPERATOR_MANAGED(HttpStatus.CONFLICT,
  "Operator-managed tag key")` — and its `status()` stub (`UnsupportedOperationException` today) is
  implemented. `ApiExceptionHandler` maps `TagOperatorManagedException` → 409 problem+json with
  that code. `catalog-api.yaml`'s `ProblemDetail.errorCode` enum (line ~700) gains
  `TAG_OPERATOR_MANAGED`. *(Seam verified: the enum is empty; the advice and yaml enum locations
  read from source.)*
- **The operator path** — the catalog service's **first** internal bootstrap endpoint:
  `POST /internal/bootstrap/resource-tags` on a new `InternalBootstrapController` (mirror the
  existing in-network `InternalOwnershipController` posture; `SecurityConfig` already permits
  `/internal/**` in **both** branches — no security change). Body `{resourceType, resourceId,
  tags}`; semantics: **merge-upsert** — only the posted keys change; a posted `null` value removes
  that key; every other key on the resource is preserved. Values are still dictionary-validated
  (enum legality via the same `fetchApplicable`+`validatedValue` path) but the operator-managed
  rejection is **bypassed by construction**. **Dictionary addressing is by the posted
  `(resourceType, resourceId)` as-is** — B's only operator use is `env` (GLOBAL) on catalogs, where
  root == self; operator writes of team-scoped keys on non-root resources are out of scope in B
  (an unresolvable key is the ordinary 422). Consumers: T6's runner (and any operator). 404 for an
  unknown resource; 422 for an unknown key or illegal value.
- **Documentation delta (this commit):** extend T1's guide subsection with the enforcement point +
  the operator path (one paragraph); note in `scripts/postman/README.md` is T6's.

**Acceptance.** **U3**, **U4** · **I3**, **I4**. Non-happy paths are the point: I3 asserts the 409 body's
`errorCode`; I4 asserts 422 on an illegal enum value and 404 on an unknown resource through the
operator path itself.

**NOT to touch.** No policy files. No library modules. The public tag flows' behavior for
non-operator-managed keys is byte-identical (U4's non-managed-key control case). `TagDefinitionClient` fetch/guard
behavior unchanged (no caching added — it has none today, verified).

---

## T3 — library: `Resource.root_attributes` + manager-side governing-target enrichment

**Goal.** The published input contract gains the three-state `root_attributes` field (ADR 0032),
populated by the authorization manager from the governing target it already computes — additively:
every existing consumer, input, and test unchanged.

**Deliverables.**
- `opa-abac-core` — `AbacContext.Resource` gains the **fifth component**
  `@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> rootAttributes` (serialized
  `root_attributes` — match the existing snake_case wire naming; verify the record's naming
  strategy against `ancestors`' serialization before assuming an annotation is needed) + compat
  constructors so the existing 3-arg and 4-arg callers compile and serialize **byte-identically**
  (the `ancestors` evolution pattern). **Canonical-constructor copy defends null-PRESERVINGLY for
  this one component** — `rootAttributes == null ? null : Map.copyOf(rootAttributes)` —
  deliberately unlike `attributes`/`ancestors`, whose null→empty normalization would merge the
  absent and untagged states and turn enrichment failure into the untagged-OPEN tier.
  **NON_NULL, never NON_EMPTY** — `{}` (untagged root, fetched) must serialize; `null` (no
  enrichment) must not; the wire name needs an explicit `@JsonProperty("root_attributes")` (the
  record uses per-field naming, no strategy — verified). *(Seam verified: record read at
  `AbacContext.java:55-73`; existing constructor call sites, 3- and 4-arg: the manager
  `:188/:206/:219` (3-arg) and `:285` (4-arg), `OpaAuthorizationManager:72` (3-arg),
  `ActionEnrichmentAdvice:227` (4-arg), both list authorizers — all compile via compat ctors,
  none change.)*
- `opa-abac-spring-security` — `OpaPreAuthorizeAuthorizationManager` populates it, **one rule, both
  paths** (ADR 0032 §Population as amended):
  - `resolveInstance` (`:262-287`): after computing `governingRoot` (`:283`), when it is
    **distinct from the leaf**, resolve it via `resolutionSupport.resolver().resolve(...)` and
    thread its `abacAttributes()` into the widened `Resource` (`:284`). Leaf-is-root → `null`.
  - the **type-level branch** (`:201-206`) + `withRoleResourceOverride` (`:236-254`): when the
    override resolves (`roleType/roleId`, `:253`), resolve the override target the same way and
    thread its attributes into the type-level `Resource`. No override → `null`.
  - **Any failure** (resolver empty, resolver throw, `resolutionSupport == null`) → `null`
    (absent) — logged at debug, **never** a deny by itself and **never** an exception out of the
    manager: the policy decides what absence means (member paths are indifferent; the supervised
    deny closes).
  - The root resolve is **read-through-memoized** in the existing `RequestAttributesResourceCache`
    idiom (`:16-41`; get before resolve, put after) so a request pays at most one extra resolver
    call across gate + instance checks. *(Seam verified: cache class + manager write-through read
    from source.)*
  - **The root memo put is decision-independent** (put after a successful resolve, before the OPA
    call) — this **deliberately amends the cache contract**: entries become *resolved* snapshots,
    no longer only *authorized-on-allow* snapshots (the manager's `:129-134` write-through). Update
    the manager javadoc (`:50`) and the `:130-133` comment **in this commit** so the two stories
    don't diverge; the gate-never-reads-the-cache note applies to the *decided leaf*, not the root
    memo.
- **Documentation delta (this commit):** the *Root-attribute enrichment* section in
  `docs/guides/ABAC-AUTHORIZATION.md` — the three states, the NON_NULL rationale, the
  naive-negation trap (`not root_attributes.env == "production"` is wrong in Rego), pointing at
  ADR 0032.
- **Build-breaker note:** widening the record's canonical constructor breaks any test constructing
  it positionally with 4 args ONLY if no compat ctor is kept — keep both compat ctors; the module's
  existing tests must pass **unmodified** (that is U14's assertion, and the additivity proof).

**Acceptance.** **U12**, **U13**, **U14**, **U15** · (T5 proves the end-to-end shape). Non-happy paths: U13's
resolver-throw and no-support cases land on absent, and U14's declared-but-unresolvable override
still denies before enrichment is ever attempted (the existing `:248-252` fail-closed branch —
assert it did not regress).

**NOT to touch.** `opa-abac-core` stays Spring-free (the record change is pure Jackson/Java).
`AbacResource`, `AbacResourceResolver`, `ParentRef`, both ancestor resolvers: **untouched** (ADR
0030 §4 / ADR 0032 rejected alternatives). No policy files. No example-app files (T5's).
`opa-abac-spring-data` untouched — `filter` inputs gain nothing (the tier never enters the
residual).

---

## T4 — the widened supervisor role + the tier-deny clauses + the policy proof

**Goal.** A supervisor's synthesized role can open child contents through the ordinary direct-grant
path, and the corpus closes exactly the production / unproven cases — provable by `opa test` alone.

**Deliverables.**
- `SupervisorRoles.readOnlyFor` (`:78-83`): when the supervised type is **`"catalog"`** (the only
  governing type teams target today), the synthesized role grants
  `{catalog: ["READ"], category: ["READ"], product: ["READ"]}`; any other type keeps the
  single-key shape unchanged. Same code, same provenance attribute, still never stored. *(Seam
  verified: current method quoted from source.)*
- `infra/opa/policies/category.rego` + `product.rego`: **two new `denied` clauses in each** (the
  slice's ONLY policy change, four clause sites):

  ```rego
  denied if {                       # tier unproven — enrichment failed or absent
      input.role_definition.attributes.provenance == "supervised"
      not input.resource.root_attributes
  }

  denied if {                       # tier proven production
      input.role_definition.attributes.provenance == "supervised"
      input.resource.root_attributes.env == "production"
  }
  ```

  `denied` is consulted by **both `allow` clauses** (instance + type-level gate) and by `bulk` —
  and **deliberately not** by `filter` (verified: `filter` never reads `denied` in either file;
  the coarse gate is where the list tier decision lands, and a `root_attributes` predicate must
  never enter the SQL residual). `catalog.rego` untouched — root metadata stays ungated (ADR 0030
  §1). *(Seams verified: allow/denied/filter shapes quoted from both files.)*
- `category_test.rego` + `product_test.rego`: the U6–U11 cases — the tier states on the instance
  shape and the type-level gate shape (reuse `list_gate_input` / `product_list_gate_input`),
  member-unaffected (membership provenance + production → allow; membership + absent → allow), and
  **one clause-deletion guard per clause site**: for each of the four new clauses there is at
  least one test that fails if that clause alone is deleted (the slice-A layer-3 lesson: a
  conjunct across N sites needs a guard per site — mutation-check this during the ticket, not
  after).
- **Documentation delta (this commit):** the *Production tier* subsection in
  `docs/guides/TEAM-BASED-AUTHORIZATION.md`'s supervised-scope section — the widened role, the
  deny shape, the three states, the member-unaffected scoping. *(T6 later adds only the e2e/E6-flip
  paragraph — section ownership split is explicit to keep the parts from colliding in one file.)*

**Acceptance.** **U5** · **U6–U11** (U6–U10 `opa test`; suite grows from 276; U11 is the
recorded mutation check) · the four deletion-mutation results recorded in STATUS-04. Non-happy path: the
unproven-state denials **are** the non-happy path.

**NOT to touch.** `permissions.rego` + `permission_categories.json` (the mirrored bundle — no new
verb, ADR 0030 §1). `catalog.rego`, `team.rego`, `agent_tools.rego`. The `filter` rules in both
files. ADR 0031's existing conjuncts and their tests. No Java outside `SupervisorRoles` (+ its
test).

---

## T5 — catalog-service ITs: the four child endpoints' tier behavior below the rig

**Goal.** Prove, against real Postgres and a recording OPA stub, that every one of the four child
endpoints sends the pinned input shape and honors the decision — including both failure states —
before the rig ever runs.

**Deliverables.**
- ITs in `example-catalog-management-service` (Testcontainers Postgres — never H2; the in-process
  `com.sun.net.httpserver.HttpServer` OPA stub — no WireMock) covering:
  - **I5**: child GET (`getCategory`, `getProduct`): the recorded OPA input carries
    `resource.root_attributes` equal to the parent catalog's tag map; `{}` for an untagged
    catalog; the endpoint returns 403 when the stub answers deny (the plain-deny mapping —
    no `deny_reason` anywhere in B).
  - **I6**: child LIST (`listCategories`, `listProducts`): the **coarse-gate** input (type-level,
    `resource.id == null`) carries `root_attributes` from the roleResource override target; the
    residual/filter request that follows carries **none** (the tier never enters the residual).
  - **I7**: a resolver whose root fetch **throws** → the gate input has **no** `root_attributes`
    key (absent, not `{}`, not `null`-serialized) — asserted on the raw recorded JSON.
  - **I8**: one root resolve per request across the gate + any instance check (the
    `RequestAttributesResourceCache` ride) — asserted by counting resolver invocations.
- Whatever minimal app-side test scaffolding this needs (a counting/throwing test resolver bean) —
  **no production-code change is expected in this ticket**; if one turns out to be needed, that is
  a seam deviation to record in STATUS-05 *Decisions* before proceeding.
- **Documentation delta:** none — T3's guide section covers the mechanism; state that explicitly
  in STATUS-05.

**Acceptance.** **I5–I8** green in `./gradlew build`.

**NOT to touch.** No library changes (T3 is closed by now; a finding that reaches it is a
cross-part escalation, not a local fix). No policy files. No `scripts/postman/`.

---

## T6 — e2e: the production-tier matrix, the E6 flip, and non-regression

**Goal.** The tier holds through the gateway: non-prod contents open, production closed, the tag
unstrippable, the flip live on the next request, members and affordances honest — and slice A's
matrix updated to the B-era contract it knowingly changes.

**Deliverables.**
- `scripts/postman/production-tier-matrix.postman_collection.json` +
  `scripts/postman/run-production-tier-matrix.sh` — fixture prefix **`ffff…`** (verified free
  repo-wide), reusing the `sup-*` realm personas and the in-network `mint_token()` idiom from
  `run-supervised-scope-matrix.sh` (`:79-86`), the internal bootstrap calls via host `curl`
  against the published service ports (the shipped mechanism — user-service
  `localhost:28090`-style; add the catalog service's published port for the new
  `/internal/bootstrap/resource-tags`). Rig flavour: `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1
  ./deploy.sh up --pods 2`, **zero realm diff**. Cells E1–E7 (see `10-QA-TEST-CASES`), asserted
  with **throwing** assertions per the E2E-TESTING assertion-style convention (`pm.response.to.
  have.status(...)` / `pm.expect` — never a returned boolean), on exact ids/counts/error codes,
  teardown-on-green.
- **The E6 flip in A's matrix** (`supervised-scope-matrix.postman_collection.json`): E6a/E6b/E6c
  (currently 403 on `dave_catalog`'s categories/category/product — quoted from source) become the
  B-era contract: **200 with exact-id assertions** (untagged ⇒ non-production ⇒ open), and E6a's
  "drifted into slice B" comment is replaced by one pointing at B's matrix as the owner of the
  closed-contents proof. A's read-only-ceiling and E4 cells: untouched.
- The `ffff…` row in `scripts/postman/README.md`'s fixture-id registry (contiguous with the table
  — no blank line; the orphan-row lesson).
- **E8 non-regression, enumerated**: re-run on this rig flavour at minimum
  `run-supervised-scope-matrix.sh` (the role and policy changed under it), `run-tests.sh`,
  `run-filter-matrix.sh`, `run-hierarchy-list-matrix.sh`, `run-isolation-matrix.sh`,
  `run-action-enrichment-matrix.sh` (the `_actions` contract's neighbors) — and record in
  STATUS-06 exactly which ran and which were skipped, with the reason (the flavour-superset lesson:
  if a matrix preflight-requires `ENABLE_DIRECTORY=1`, run the whole set on that superset flavour
  instead).
- **Documentation delta (this commit):** the e2e/E6-flip paragraph appended to T4's guide
  subsection (that paragraph only — T4 owns the rest of the section), the registry row above, and
  the **existing supervised-scope row** in `scripts/postman/README.md` updated — it currently pins
  E6 as "contents closed … each 403"; post-flip it must say untagged supervised contents are OPEN
  (200, exact ids) and point at this matrix as the owner of the closed-contents proof.

**Acceptance.** **E1–E8**. The headline cells: E4 (tier-flip liveness) and E5 (the strip attempt
asserting `TAG_OPERATOR_MANAGED` by code, not just 409).

**NOT to touch.** No Java, no policy. Other matrices' fixture prefixes (registry discipline). The
supervised matrix's non-E6 cells.

---

## Cross-cutting acceptance

- `./gradlew build` green at every checkpoint (all modules; Testcontainers ITs; `ddl-auto:
  validate` boots clean from T1 on).
- `opa test infra/opa/policies/` green at every checkpoint — unchanged until T4, grown after.
- Local Sonar CLEAN on changed files for every `.java` ticket (the ★gate's static-analysis check).
- Every existing library test **unmodified and green** through T3 (the additivity proof).
- The two failure classes stay distinct end to end: enrichment failure ⇒ absent ⇒ supervised
  closed, members proceed; nothing in the slice may convert it into a 5xx or an exception (T3) or
  a member-visible change (T4).
- Clean-room: no consumer names; commit identity `Void3110 <void31102025@gmail.com>`.
