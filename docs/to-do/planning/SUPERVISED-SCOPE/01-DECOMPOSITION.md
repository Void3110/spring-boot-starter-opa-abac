---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# SUPERVISED-SCOPE — decomposition

> T1…T6, in order. Each ticket is one focused commit's worth of work.
> Design: [[00-DESIGN]] · Contract: [[0029-supervised-read-scope|ADR 0029]] · Cases: [[10-QA-TEST-CASES]]

## Critical path

```
T1 ──► T2 ──► T3 ──► T4 ──► T5 ──► T6
(user-svc)  (user-svc)  (policy)   (catalog-svc) (catalog-svc)  (e2e + docs)
```

**Strictly sequential.** T2 needs T1's derivation to answer the supervisor branch; T3 confines the role
T2 synthesizes; T4 consumes T2's endpoint over HTTP; T5 composes T4's ids into the list; T6 proves the
whole path through the rig. There is no parallel landing worth the coordination at this size.

**Standalone-value subset: T1 + T2 + T3.** Together they make the user-service answer "who does this
subject supervise, and with what role" **and** make the corpus provably deny that role any child access —
a complete, tested capability (ITs + `opa test`, no catalog service, no rig), useful to the next slice
even if the window closes before the catalog side lands. Nothing user-visible changes until T5.

**Two deployables plus the policy corpus, in dependency order** — the user-service (T1, T2), the OPA
policies (T3), then the catalog-service (T4, T5). **No library module is touched in this slice.**

## Two pinned semantics (so the run never stops to ask)

1. **`supervised := S \ M` is computed on the catalog side, in T5** — not in the user-service. The
   user-service answers the raw supervised set; only the list authorizer knows both sets. The endpoint's
   contract is therefore "the catalogs this subject supervises", membership notwithstanding.
2. **A supervised catalog whose role no longer resolves is dropped, not defaulted.** Same rule as the
   shipped membership leg: a `null` role compiles the residual to `DENY_ALL` → that leg contributes
   nothing. Never substitute a fallback role.

---

## T1 — user-service: the reporting relation, transitive derivation, and `/internal/supervised-targets`

**Goal.** The user-service can answer, fail-closed, which catalogs a subject supervises — derived from
reporting edges through CONTROL-capable memberships, depth-capped and cycle-guarded.

**Deliverables.** Package `dev.dmitriikonovalov.example.usermgmt.{domain,service,web}`:

- `ReportingEdge` entity (`manager_id`, `report_id`) + `ReportingEdgeRepository`, and a Liquibase
  changeset `000X-create-reporting-edge.yaml` with a unique constraint on the pair and an index on
  `manager_id`. Follow the numbering already present in
  `example-user-management-service/src/main/resources/db/changelog/changes/`.
- `SupervisionService` with:
  - `Set<UUID> transitiveReportsOf(UUID managerId)` — breadth-first, visited-set cycle guard,
    **manager-exclusive**; returns an **empty set** on a cap breach or a detected cycle (never a
    partial set), logging one WARN. Never null, never throws.
    **Depth is counted in HOPS from the manager — a direct report is hop 1. Hops 1–10 inclusive are
    derived; discovering an 11th hop is a cap breach and collapses the whole result to empty.**
    (U33 pins the inclusive boundary; a cap breach zeroes the entire set, so an off-by-one silently
    empties a legitimate manager's page.)
  - `List<UUID> supervisedTargets(String subject, String resourceType)` — the reports' teams filtered to
    **CONTROL-capable** seats, then those teams' governed targets. Reuse the shipped
    `TeamRoleCapabilities` ladder for the capability test — do **not** re-enumerate role codes inline.
    Distinct ids, empty on any breach.
- Cycle/self-edge rejection **on write**, surfacing as `422` through the existing `ApiExceptionHandler`
  (reuse the shipped error-code vocabulary; extend the closed enum if and only if no code fits, per
  `mx-fb443b`).
- `@GetMapping("/internal/supervised-targets")` on the existing `InternalResolveController` —
  `@RequestParam("subject") String`, `@RequestParam("resourceType") String`, returning
  `ResponseEntity<List<UUID>>`, **always 200** with a possibly-empty array. *(Signature mirrors the
  shipped `/internal/governed-targets` on the same controller — verified by reading
  `InternalResolveController`.)*
- `@PostMapping("/internal/bootstrap/reporting-edges")` on `InternalBootstrapController`, mirroring the
  shipped bootstrap endpoints, so fixtures seed edges the same way teams and memberships already are.
- **A removal seam — E4 is unexecutable without one.** Every shipped bootstrap endpoint is
  upsert-only (verified: four `ensure`-shaped POSTs, no delete anywhere), but E4 — the slice's headline
  **liveness** proof — requires *removing* a report and observing access withdraw on the next request.
  Ship the narrowest thing that satisfies it: make the bootstrap POST **declarative for a manager's
  edge set** (the posted set replaces that manager's edges, so an empty set removes them), or add an
  explicit `DELETE /internal/bootstrap/reporting-edges`. Pick one, state which in `STATUS-01.md`, and
  keep it `/internal`-only — this is fixture plumbing, not a public API.
- **OpenAPI: no change.** `/internal/**` endpoints are hand-written and deliberately absent from
  `user-mgmt-api.yaml` (which is public-API-only and drives codegen) — the four shipped internal
  endpoints are excluded the same way. Document both new contracts in the controller javadoc, exactly
  as `/internal/governed-targets` is.

**Acceptance.** QA **U1–U10**, **U33**, **I1–I2**. `./gradlew :example-user-management-service:test` green and
`./gradlew build` green. I1 asserts the supervised id set **by id**, not by count.

**What NOT to touch.** `EffectiveRoleService`'s existing resolve paths (that is T2) and
`governedTargets` (unchanged — the new method sits beside it). No catalog-service file. No library
module. The **fail-closed floor**: every failure in this ticket returns an empty collection, never a
partial one and never a throw reaching the controller as a 500. `ddl-auto: validate` must boot clean —
a clean boot is the proof the changeset matches the entity.

---

## T2 — user-service: the non-membership `effective-role` branch + the synthesized supervisor role

**Goal.** A subject who supervises a catalog but is a member of no team resolves to a synthesized,
read-only role instead of `204` — carrying provenance, and granting nothing on child types.

**Deliverables.** Package `dev.dmitriikonovalov.example.usermgmt.service`:

- `SupervisorRoles` constants: the reserved role code and the provenance attribute key/value. Codes are
  only *partially* unique against custom roles — record in `STATUS-02.md` that this is **not** an
  escalation (reach comes from T1's derivation, never from the role), per ADR 0029 §7.
- `EffectiveRoleService.resolveForResource` gains an ordered fallthrough:
  1. a membership role resolves → **return it** (precedence: membership always wins, U15);
  2. else the subject supervises this resource → return the **synthesized** supervisor role;
  3. else `Optional.empty()` → the controller's existing `204`.
- The synthesized `RoleDefinition`: `permissions = {"catalog": ["READ"]}` — the **coarse category
  token**, and **no `category` key, no `product` key, no `"*"` key** — empty `requiredTags`, the
  reserved code, and the provenance marker in the existing generic `attributes` map. Built in code,
  **never persisted**.
  > **Tokens, not verbs.** Since ADR 0007/0015 `permissions[<type>]` carries the coarse tokens
  > `READ`/`WRITE`/`TAG`/`GRANT`/`CONTROL`, which `data.permission_categories` expands to fine verbs;
  > `READ` expands to `view, list, list-members`. Writing the fine verbs instead expands to the
  > **empty set** (the documented fail-closed ∅-expansion for a pre-6.5 flat role), so the role would
  > grant nothing and the supervised page would be silently empty. Never write fine verbs into a
  > `RoleDefinition`.

**Acceptance.** QA **U11–U16**, **I3**. `./gradlew :example-user-management-service:test` green.
U14 is the boundary assertion and must be checked **against the policy, not only the Java shape** —
assert via `opa eval` that `data.catalog.filter` is **true** under the synthesized role. A Java-only
assertion would pass on a role that grants nothing.
**U14 asserts the role SHAPE and `data.catalog.filter` — not "contents are closed."** That half cannot
hold at T2: the confinement rule lands in T3, and an ancestor-less probe would return `false` for the
wrong reason (precisely how the original case green-lit a live fail-open — ADR 0031 §Context). The
honest, ancestor-carrying contents assertion is **U35/U36, owned by T3**.

**What NOT to touch.** The batch `/internal/effective-roles` path and its strict one-entry-per-target
contract (ADR 0024) — it resolves per target through the same service, so verify by test that the
supervisor branch flows through it consistently rather than special-casing it. The core
`RoleDefinition` record — the marker rides the **existing** `attributes` map; adding a field would be an
envelope change this slice forbids. No Rego. No library module.

---

## T3 — confine ancestor inheritance to membership-derived roles

**Goal.** The synthesized supervisor role can reach **no** child type, while every membership-derived
role keeps the inheritance it has today — closing the fail-open that
[[0031-inheritance-confined-to-membership-roles|ADR 0031]] pins.

**Deliverables.**

- `EffectiveRoleService.resourceRole(TeamMembership, String)` (user-service) stamps
  `attributes.provenance = "membership"` on every role it returns — by **overwrite, never merge**.
  A stored role's `attributes` map is **client-supplied** through the role create/update API and is
  copied verbatim onto the wire role, so `provenance` is a **reserved, system-owned key**: strip a
  client-supplied value on the write path (`RoleDefinitionService`, mirroring the shipped
  `withRoleLevel()` — "the explicit value is the single source; an attributes-supplied value is
  overwritten") and overwrite on the read path. Without both, a client could set
  `provenance: "membership"` on a custom role and buy back the inheritance this ticket denies the
  moment any future path returns a stored role without passing through the funnel. It is the single construction site for roles reaching the
  **catalog-side** policies (verified: one call site, from `resolveForResource`, which the batch
  `/internal/effective-roles` also routes through — re-confirm before relying on it).
  `managementRole(...)` is a **second** construction site, but it serves the user-service's own
  `team.rego` decisions and `team` has no inheritance table, so the conjunct never applies there —
  **do not** stamp it as membership-derived by reflex; if `team.rego` ever gains inheritance, this
  decision must be revisited.
- T2's synthesized supervisor role carries `attributes.provenance = "supervised"` — the **same key**
  that satisfies the design's provenance/audit pin, one marker serving both.
- `infra/opa/policies/category.rego` and `infra/opa/policies/product.rego`: the conjunct
  `input.role_definition.attributes.provenance == "membership"` added to **all four** clauses —
  `inherited_grant` and `list_inheritable_grant` in each file.
- Rego test cases in `category_test.rego` / `product_test.rego` (the six below), plus the existing
  inheritance-dependent fixtures updated to carry the stamp. **Measured, not estimated:** applying the
  conjunct to an unmodified corpus takes `opa test infra/opa/policies/` from **266/266 to 261/266** —
  exactly **five** existing cases rely on inheritance and need the stamp added. If a sixth breaks, stop:
  something outside this ticket's model depends on inheritance.
- **One test at the seam** (user-service tier, alongside the existing tests that already exercise
  `resourceRole`) asserting the stamp is applied — `opa test` fixtures are hand-written and would stay
  green if the Java silently stopped stamping.
- Docs delta: a subsection in `docs/guides/TEAM-BASED-AUTHORIZATION.md` (the guide that owns this
  surface, per the design's knowledge destination) stating the invariant — inheritance requires
  membership provenance; a synthesized role is confined to the types it names.

**Acceptance.** QA **U35–U41** + **I7**. Run `opa test infra/opa/policies/` (all green, existing cases
included) and the seam test — **no rig, by design**: part 0 must stay provable with ITs plus `opa test`
alone. The **rig-level** regression for this change (the existing matrices that read categories/products
as a member) is **T6's** E7 run, which is where the rig comes up; a red cell there is a stamp bug, not a
flaky rig, and that diagnostic is recorded in T6.

**What NOT to touch.** `permissions.rego` and `permission_categories.json` — the conjunct may **NOT** be
centralized into `permissions.effective_actions`: direct grants use the same helper (the supervisor
would lose its own `catalog:view`), and both files are **byte-mirrored** into
`example-user-management-service/src/main/resources/opa/policies/`, so editing them opens a drift
surface for no benefit. `category_inheritable.json` / `product_inheritable.json` (the tables stay as
shipped — the constituency that legitimately inherits keeps it). Stored role rows (no migration; the
stamp is applied at resolution). `catalog.rego` (the root list is governed by `filter`, not
inheritance). **Fail-closed floor:** an unstamped role, an empty `attributes`, or an unknown provenance
value grants **no** inherited access — absence is closed, never open.

---

## T4 — catalog-service: the `SupervisedScopeClient` HTTP edge, fail-closed and resilience-wrapped

**Goal.** The catalog service can fetch a subject's supervised catalog ids, degrading to an empty list on
every failure class without ever throwing into the request.

**Deliverables.** Package `dev.dmitriikonovalov.example.catalog.config`:

- `SupervisedScopeClient` calling
  `GET <base>/internal/supervised-targets?subject=&resourceType=`, modelled **directly** on the shipped
  `HttpGovernedScopeResolver` in the same package — same classification discipline (only `200` + a valid
  body yields ids; every other outcome is an empty list plus one WARN), same timeout handling, same
  interrupt-flag restoration.
- Wire it through a **dedicated** guard — add a `supervisedCallGuard` bean to the example's
  `CatalogResilienceConfig`, built from the existing `opa.abac.resilience.resolve.*` budget but as its
  **own breaker instance**, and inject it with `@Qualifier("supervisedCallGuard")` exactly as
  `HttpRoleDefinitionSupplier` uses `resolveCallGuard` and `TagDefinitionClient` uses `tagCallGuard`
  (B3, ADR 0017). **Do NOT reuse `resolveCallGuard`**: it guards `/internal/effective-role`, so sharing
  it would let a supervised-targets outage trip the breaker that every persona's role resolution
  depends on — turning a degrade-to-membership-only into an empty page for everyone, and making U30
  and E8 unsatisfiable.
- **Record a breaker failure only on the thrown retryable path** — a fail-closed empty result is a
  *decision*, not a transport failure (`mx-951d2f`).
- A **dedicated** configuration property for the supervised base URL —
  `catalog.user-service.supervised-base-url`, **defaulting to `${catalog.user-service.base-url}`** so the
  shipped rig is unchanged. It must be its **own** property, not the shared one: E8 faults *only* this
  edge by repointing it, which is impossible if the supervised client reads the shared URL (B3's stub
  swaps the whole user-service the rest of the matrix needs). The bean is
  present only when the user-service edge is configured, absent otherwise (so the list simply has no
  second leg — see T5).

**Acceptance.** QA **U17–U24**. `./gradlew :example-catalog-management-service:test` green. Tests use an
**in-process `com.sun.net.httpserver.HttpServer`** stub — never WireMock. U19–U23 are the ones that
matter: each failure class returns empty rather than propagating.

**What NOT to touch.** `HttpGovernedScopeResolver` itself (unchanged — the new client sits beside it),
`HttpRoleDefinitionSupplier`, and the shipped `CallGuard` configuration. Do not make this client throw a
tri-state outage signal: the **base-scope** SPI shape is fail-closed-to-empty, unlike the role supplier
(`mx-1ce7d5` records the distinction). No library module.

---

## T5 — catalog-service: the two-leg partitioned list, the read-only ceiling, and the audit event

**Goal.** `GET /catalogs` returns membership rows **and** supervised rows, each judged by its own role,
with the supervised rows read-only and audited — and existing personas byte-identical.

**Deliverables.** Package `dev.dmitriikonovalov.example.catalog.config`:

- `CatalogListAuthorizer.readable` composes the two **disjoint** scopes through the **shipped**
  `findAuthorized` **paged 5-arg** overload (the unpaged 4-arg form is the `List` variant; the list endpoint is paged) — the ADR-0010 base-scope-widening idiom, reused rather than
  reinvented. `M` comes from `GovernedScopeResolver.governedIds`, `S` from `SupervisedScopeClient`,
  then **`supervised = S \ M`** (ADR 0029 §5). Three cases, all using the same shipped call:

  | Case | `scope` | context role | `subtreeSpec` |
  |---|---|---|---|
  | both non-empty | `id IN (M ∪ supervised)` | the **membership** role | `id IN supervised` |
  | `M` empty (a pure supervisor) | `id IN supervised` | the **supervisor** role | `null` |
  | `supervised` empty (an ordinary member) | `id IN M` — **today's call, unchanged** | the membership role | `null` |

  The library composes `scope ∧ (residual ∨ subtreeSpec) ∧ notDenied()` **on its pure-SQL branch** (see
  [[00-DESIGN]] §5's pinned semantic (the branch limitation) for the two branches that ignore `subtreeSpec`, and U42), so in the
  mixed case a
  membership row is judged by the membership residual while a supervised row is admitted by the
  `subtreeSpec` arm — each row judged by the authority that earned it, with the deny-override still
  AND-ed outside.
  > **`findAuthorized` compiles exactly ONE residual, from the single `AbacContext` it is given.**
  > There is no overload taking two `(scope, context)` legs and no public method turning a role into a
  > residual `Specification`. Passing a pre-composed `legA.or(legB)` as `scope` would get that one
  > residual AND-ed over the whole union — narrowing the supervised rows by the membership role. The
  > `subtreeSpec` slot is the shipped way to express "admit these rows too".
  - **PRECONDITION — assert it (U34), do not assume it.** Admitting supervised rows through
    `subtreeSpec` is correct **only because the supervisor role's residual is unconditional**
    (`READ` with empty `requiredTags` → `ALLOW_ALL`). If a later slice gives that role a tag
    requirement, this composition must change. U34 is what makes the coupling visible.
- **Retire `governedIds.get(0)` and replace it explicitly** — the case table above *is* the replacement,
  and a wrong guess here is the slice's fail-open. Restated as a rule: **whenever `M` is non-empty the
  residual-driving role is resolved from a MEMBERSHIP id, never from the `M ∪ supervised` union** (a
  supervised id selecting it would let the supervisor role's vacuous tag requirement judge tag-gated
  membership rows). Only when **`M` is empty** — the pure-supervisor case — is the role resolved from a
  supervised id, which is correct because there are no membership rows for it to widen. Retire it
  together with the Javadoc paragraph justifying it ("every governed
  catalog is one the subject is a member of") — that assumption is exactly what this slice breaks.
  **Build-breaker: any existing test asserting the single-role shape must be updated in this same
  commit.** Scout them first (`CatalogListAuthorizer`-referencing tests, `PaginationEnvelopeIT`).
- A dedicated audit logger — the SLF4J logger named
  **`dev.dmitriikonovalov.example.catalog.audit.SupervisedRead`** (pinned so I6 can assert it by name,
  and so a consumer can route it independently) — emitting **one event per supervised list REQUEST, at
  `INFO`, only when the supervised leg contributed at least one row** (a request whose supervised leg is
  empty emits nothing — otherwise every ordinary list by a supervisor-eligible subject would log). The
  payload carries subject, access path, and the **supervised root ids as a list** — plural, because a
  page can span several supervised roots, which a singular "root id" cannot express. **No event** on a
  membership read. Nothing persisted.
  **Scope, pinned:** this slice audits the **list** path only, because that is where the supervised
  authority is applied; supervised single-`GET` auditing rides the `@OpaPreAuthorize` gate and is
  **deferred** (it lands naturally with the slice-C audit work). I6 asserts the list event; no case
  asserts a single-GET event.
- Verify the `_actions` affordance on a supervised row is `{view:true, …mutations false}` and **present,
  not omitted** — the omit-on-all-false degrade fires only when *every* verb is false (`mx-cc7262`), and
  `view` is true here. **Verify the verb set against the real endpoints, never assume it**
  (`mx-3446c4` records two corrections caught exactly this way).

**Acceptance.** QA **U25–U32**, **U34**, **U42**, **I4–I6**. `./gradlew :example-catalog-management-service:test` green and
`./gradlew build` green. **U25 is the non-regression assertion** — with an empty supervised set the page
must equal today's result exactly. I4 asserts paging correctness across the union (no duplicate, no row
skipped at a boundary).

**What NOT to touch.** `AbacQueryService` and the partial-eval residual compiler (this ticket composes
Specifications; it does not change how residuals are built). `CategoryListAuthorizer` and
`ProductListAuthorizer` — child lists stay closed in this slice. The `notDenied()` AND that
`AbacQueryService` applies unconditionally. **Fail-closed floor: every branch lands on the empty page,
never the table** — including both-scopes-empty, an unresolvable role on either leg, and a supervised
fetch failure (which degrades to **membership-only**, U30). No Rego. No library module.

---

## T6 — e2e matrix, demo personas, and the guide

**Goal.** Prove the whole path through the rig against exact ids, prove nothing else regressed, and
document the second access path.

**Deliverables.**

- **Realm accounts (`infra/keycloak/realm-export.json`).** An e2e persona **is** a Keycloak user — every
  matrix mints its token by password grant against `catalog-demo` — so the personas must be added to the
  realm export: **`sup-anna`**, **`sup-victor`**, **`pm-bob`**, **`pm-carol`**, **`outsider-eve`** (all five are NEW
  accounts — the realm ships no `pm-*` user; the existing demo users are untouched), plus the **UX-only `unit-supervisor` realm role** that E10
  asserts grants nothing. This is the **only** realm change slice A makes — see the narrowed scope
  boundary in [[00-DESIGN]]; the `acr`/`auth_time` scopes, mappers, the conditional flow and any new
  client remain slices B and C.
- Org + team fixtures in the `scripts/` seed data: `sup-anna` is a member of **no** team and reports
  `pm-bob` + `pm-carol`; `pm-carol` has her own report (→ transitivity); `sup-victor` supervises a
  disjoint unit; `outsider-eve` has nothing. Reporting edges seeded through the T1 bootstrap endpoint;
  every new subject registered in the fixture-id registry the suite already keeps
  (`scripts/postman/README.md`).
- `scripts/postman/supervised-scope-matrix.postman_collection.json` +
  `scripts/postman/run-supervised-scope-matrix.sh`, modelled on the shipped matrices, entering **through
  the gateway** and asserting the **actual cut** — exact ids and counts, allow-vs-deny contrast, and the
  fail-closed negatives.
- **E8's fault injection is its own edge, not B3's.** B3's mechanism (`ENABLE_RESILIENCE_STUB=1`,
  `mx-91fa5d`) repoints `CATALOG_USER_SERVICE_BASE_URL` at the stub and therefore **replaces the whole
  user-service the rest of this matrix needs** — it cannot fault only the supervised edge. Instead: T4
  gives the supervised client its **own** base-URL property (defaulting to the shared
  `catalog.user-service.base-url`), and E8 runs as a **second short pass on the same rig** with only
  that one env var repointed at a dead port, then the catalog pods recreated. Record the two-pass shape
  in the matrix README. **T6 owns the rig plumbing this needs**: pass the property through to the
  catalog pods as an env var in `deploy.sh` (mirroring how `CATALOG_USER_SERVICE_BASE_URL` is already
  passed), so the second pass is `<VAR>=http://127.0.0.1:9 ./deploy.sh up --pods 2` + a pod recreate —
  no compose-file edit, since that file is generated.
- Guide: a **new section in the existing** `docs/guides/TEAM-BASED-AUTHORIZATION.md` covering the two
  access paths, the precedence rule, the CONTROL-capable reach rule, and both failure classes. Per
  [[00-DESIGN]]'s knowledge destination, this does **not** earn a new guide.
- `infra/README.md` and `scripts/postman/README.md`: the new matrix, its flags, and the personas —
  **including a fixture-id prefix registered in `scripts/postman/README.md`'s registry** (`sup_*`,
  mirroring the shipped per-matrix prefixes). The registry exists so two matrices cannot collide on a
  fixture id; a new matrix that skips it is the collision waiting to happen.

**Acceptance.** QA **E1–E10**, **D1–D2**. `./gradlew build` green; `opa test infra/opa/policies` green
**including T3's new cases** (this slice ships exactly one narrow policy change — ADR 0031 — and no
other; a Rego edit outside T3's four clauses means the run has left the slice boundary); the new matrix
green.

**E7's non-regression run is an explicit list, not "the full suite"** — `scripts/postman/` ships 15
independent `run-*.sh` with *mutually exclusive* rig flavours (the resilience matrix needs
`ENABLE_RESILIENCE_STUB=1`, which by construction disables the real user-service the others require), and
there is **no aggregate runner**. On one `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`
rig, re-run every matrix that exercises catalog listing or role resolution — at minimum the isolation (B4's own `GET /catalogs` matrix), team, tag,
filter, hierarchy, permission-categories and resource-resolution matrices plus `./run-tests.sh` — and
record in `STATUS-06.md` **the exact list you ran and any you deliberately skipped, with the reason**.
Silently skipping a matrix would make E7 assert less than it claims.

**What NOT to touch.** Existing collections' expectations — if one needs editing, that is a regression to
investigate, not a test to adjust. The Rego policies (**zero changes**; asserting that is E7). The
`env` tag, elevation, and anything from slices B and C — **E6 exists to assert contents stayed closed.**

---

> **ADR 0029 §9's `GovernedScopeResolver` contract-text revision is DEFERRED, not owned here.** That
> revision touches a **published library module**, which this slice forbids end to end; it lands when the
> org-relation seam is promoted to a published SPI (ADR 0029's own deferred consequence). Slice A leaves
> the library untouched and composes the supervised set **beside** the resolver — no ticket owns a
> library edit, and a run that finds itself editing `opa-abac-spring-data` has left the slice boundary.

## Cross-cutting acceptance

- `./gradlew build` green (all modules + integration tests).
- `opa test infra/opa/policies/` green **including T3's six new cases** — T3 is the slice's only policy
  change (ADR 0031); a policy edit outside its four inheritance clauses means the run has left the
  slice boundary.
- The existing newman matrices that touch catalog listing or role resolution: green and unchanged, run
  on one rig flavour, with the exact list recorded in `STATUS-06.md` (there is no aggregate runner — see
  T6's Acceptance). The new supervised-scope matrix green.
- The local Sonar scan **CLEAN** on the changed files for every `.java`-touching ticket.
- `ddl-auto: validate` boots clean (T1's changeset).
- **The fail-closed invariant holds on every error path**, with both classes landing where
  [[00-DESIGN]] says: an errored org source → the subject's own memberships; a partial derivation →
  membership-only. Never a partial supervised set, never the table.
- No library module changed; `opa-abac-core` untouched and still Spring-free.

## Related

- [[00-DESIGN]] · [[10-QA-TEST-CASES]] · [[SUPERVISED-SCOPE]]
- [[0029-supervised-read-scope]] — the contract these tickets implement.
- [[0018-team-scoped-resource-isolation]] — the invariant being pierced without weakening.
- [[MULTI-TENANT-ISOLATION]] — B4, whose `governedIds.get(0)` shortcut T5 retires.
