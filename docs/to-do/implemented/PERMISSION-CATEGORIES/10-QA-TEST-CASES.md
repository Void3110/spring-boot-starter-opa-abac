---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# 10 — QA test cases: Permission categories + delegation (Phase 6.5)

> The concrete cases each [[01-DECOMPOSITION|ticket]]'s *Acceptance* references. **U** = unit (core
> record / authoring validation — no DB, no rig), **P** = policy (`opa test` +
> `opa eval --partial`), **I** = integration (user-mgmt + catalog services, real-Postgres
> Testcontainers), **E** = e2e through the gateway (newman), **D** = doc-presence checks. This is a
> **vocabulary + delegation** slice: the cases assert the expand-minus-deny algebra, the two
> assignment gates *under the team-row lock*, the TAG/WRITE boundary in **both** directions, the
> ∅-expansion fail-closed floor, and that the whole pre-6.5 suite survives the clean cut.
>
> **Negative cells name their deny mechanism** (∅-expansion · level gate · `assignable` subset ·
> denial subtraction · role-def-present-disables-fallback · no role) — a deny for the wrong reason
> is a failing cell.

## Conventions

- **Unit (core):** plain JUnit on the `RoleDefinition` record; serialization cells compare strings
  produced by the production `ObjectMapper` config.
- **Unit (authoring):** `RoleDefinitionService` validation via Mockito'd repositories — assert the
  thrown `RoleDefinitionInvalidException` per cell; the parity cell (U9) reads
  `infra/opa/policies/permission_categories.json` via the repo-relative path.
- **Policy:** `opa test infra/opa/policies/` — fixtures are **category-token** role defs (raw-row
  shape for `role.rego`, resolve-shape for the per-type policies). The PE-fold cell (P10) runs
  `opa eval --partial` against a real `opa` binary, exactly as
  [[PARTIAL-EVALUATION-FILTERING]] documents.
- **Integration:** the `AbstractPostgresIT` pattern (**real Postgres via Testcontainers**, never
  H2); OPA played by the **in-process `com.sun.net.httpserver.HttpServer` stub** (no WireMock) —
  programmable per-entrypoint verdicts; the catalog ITs use the per-action programmable stub
  (`ResourceResolutionGateIT` pattern) and assert the **decision sequence** (which actions were
  asked), not just the status.
- **e2e:** full rig — `./profile.sh up` → `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
  --pods 2`; **rebuild BOTH images** (`./deploy.sh build` covers the catalog only — build the
  usermgmt image explicitly); mint tokens **in-network** (issuer `keycloak:8888`); the runner
  **restarts OPA + health-polls** (this slice rewrites every policy). Fixture catalog
  `99999999-9999-9999-9999-999999999999` (registered; shared fixtures untouched).
- **The pinned contract** ([[00-DESIGN]] §2/§4 + the five decomposition-pinned semantics):

  | Semantic | Pinned |
  |---|---|
  | Unknown/stale permission token | expands to **∅ = deny** — never decides, never errors |
  | Authoring violation (level/token/ceiling/denial) | **`422 ROLE_DEFINITION_INVALID`** (new code; closed enum swept) |
  | Assignment rejection (level gate, senior bound, subset, **OPA error/timeout**) | **`422 ROLE_SUBSET_VIOLATION`** — one shape; outage indistinguishable from not-assignable |
  | Missing/non-numeric `role_level` on either assignment snapshot | **reject** — never 0-and-pass |
  | Update authorization | delta-aware dispatch: content → `update` · tags → `assign-tags` · both → both · **empty → `update`** |
  | Create with tags | static `create` + a **type-level** `assign-tags` second decision |
  | Admin-with-denial assigns full `WRITE` | **allowed** — the designed cross-tier cell (gate = seniority, not subset) |
  | `filter` | `"list" ∈ effective_actions` — role-def-only, **no fallback**; residual shape unchanged (PE fold) |
  | Realm fallback | `catalog-viewer → {READ}` · `catalog-editor → {READ,WRITE,TAG}` through the same table — reach identical to pre-6.5 |
  | `define-tags` | in the math (expansion/denial/subset), **not** enforced on the dictionary endpoints (control-plane gate stays) |

---

## Unit — the core wire record (T1)

| # | Case | Expected |
|---|------|----------|
| **U1** | a role constructed without denials (3-arg and 5-arg constructors), serialized | **string-equal** to the pre-6.5 form — `denied_actions` absent (`NON_EMPTY`); every existing serialization test unchanged-green |
| **U2** | JSON **without** `denied_actions` deserialized | `deniedActions() == Map.of()` — never null |
| **U3** | round-trip with `denied_actions: {"category": ["delete"]}` | survives serialize → deserialize; snake_case wire name |

## Unit — the authoring contract (T4)

| # | Case | Expected |
|---|------|----------|
| **U4** | `roleLevel` missing / `15` / `40` on create | `RoleDefinitionInvalidException` — only `{10, 20, 25, 30}` author |
| **U5** | a `permissions` value containing `"read"` (flat verb) or `"VIEW"` (a fine action) | rejected — only the four **category** tokens pass the boundary |
| **U6** | `roleLevel 20` granting `GRANT` (or 10 granting `WRITE`) | rejected — categories ⊄ `ceiling(level)`; `GRANT` only at 30 |
| **U7** | `denied_actions: {"category": ["delete"]}` while `category` grants only `READ` | rejected — **strict denial validation** (denied ⊄ expand(granted)) |
| **U8** | request `roleLevel 25` + `attributes: {"role_level": 40}` | stored `attributes.role_level == 25` — the explicit field is the single source |
| **U9** | the app's `PermissionCategories` table vs `infra/opa/policies/permission_categories.json` | **equal** (categories, expansions, per-level ceilings derived from them) — drift breaks the build |

## Policy — `opa test` + the PE fold (T2, T5)

| # | Case | Expected |
|---|------|----------|
| **P1** | `effective_actions` per category | `READ→{view,list}` · `WRITE→{create,update,delete}` · `TAG→{define-tags,assign-tags}` · `GRANT→{assign-roles}` (from `data.permission_categories`) |
| **P2** | `WRITE` granted, `denied_actions: {<type>: ["delete"]}` | effective = `{create, update}` — subtraction after expansion |
| **P3** | a stale flat token (`"read"`) or an unknown token (`"BOGUS"`) | contributes **∅** — a role holding only stale tokens decides nothing (deny; mechanism: ∅-expansion) |
| **P4** | candidate `permissions` keyed `"*"`, lookup for `"category"` | the `"*"` value is used when the type key is absent (wildcard fallback inside `effective_actions`) |
| **P5** | the per-type behavioral cells re-expressed (allow/deny/hierarchy/tag/list-gate/`bulk`) | every pre-6.5 `opa test` cell preserved at the new vocabulary — `view`/`list`/`create`/`update`/`delete` in place of `read`/`write` |
| **P6** | deny-overrides end-to-end in `category.rego`: role grants `WRITE`, denies `delete` | `update` allows, `delete` denies (mechanism: denial subtraction); the `abac_deny` resource-tag veto unchanged |
| **P7** | realm fallback: `catalog-viewer` / `catalog-editor`, no role definition | viewer reaches exactly `{view, list}`; editor exactly `{view, list, create, update, delete, define-tags, assign-tags}` — pre-6.5 reach preserved through the table |
| **P8** | `filter` with a role granting `READ` / a role granting only `TAG` / no role def | compiles to the tag residual / **deny-all** (`list` ∉ effective) / **deny-all** (no fallback) |
| **P9** | the type-level list gate (no `resourceId`) with an inheritable ancestor `READ` grant | opens for `list` exactly as it opened for `read` (5.5-B clause migrated) |
| **P10** | **the PE fold**: `opa eval --partial --format pretty -d infra/opa/policies 'data.category.filter'` with a category-token reader role (known input) + unknown `input.resource` | the residual contains **only** `input.resource…` tag conditions — the expansion folded over known input+data; shape unchanged vs the 5.x residual (paste into `STATUS-02.md`) |
| **P11** | a tag-gated category-token role (`requiredTags` + `matchMode`) | tag matching composes with expansion unchanged (ANY_OF/ALL_OF cells at the new verbs) |
| **P12** | `permissions.rego` edge algebra | empty `permissions` → ∅; denial of a never-granted action is inert at decision time (authoring rejects it, but the policy must not widen); denials never add |
| **P13** | the **`assignable` truth table** (`role.rego`, raw-row snapshots): candidate ⊆ actor → `true`; candidate grants an action the actor's denial removed → `false`; candidate's own denial rescues (`WRITE`-minus-`delete` ⊆ `WRITE`-minus-`delete`) → `true`; candidate `"*"` vs actor concrete keys (and the reverse) → wildcard-aware; candidate with an unknown token → that token adds nothing (⊆ holds vacuously); **missing input / malformed snapshots → `false`** (`default assignable := false`) | exactly as listed |

## Integration — user-mgmt (T3, T4, T5)

| # | Case | Expected |
|---|------|----------|
| **I1** | the seed migration (fresh schema boot) | `senior` row at id `…0005`, level 25, `{"*": ["READ","WRITE","TAG"]}`; `reader` present at level 10 / `viewer` absent; owner+admin carry all four categories; `member` carries `{"*": ["READ","WRITE","TAG"]}`; `denied_actions = {}` on all five |
| **I2** | `ddl-auto: validate` boot after the changelog | clean boot — entity ↔ schema agree (`denied_actions` mapped) |
| **I3** | the resolve API for a role with denials / without | `denied_actions` present (snake_case, wildcard-expanded to the target type) / **omitted** (NON_EMPTY) — the catalog's `HttpRoleDefinitionSupplier` deserializes both |
| **I4** | authoring round-trip: owner creates a level-25 role granting `{"category": ["READ","WRITE"]}` denying `{"category": ["delete"]}` | 201; read-back carries `roleLevel 25` + `deniedActions`; stored `attributes.role_level == 25` |
| **I5** | each authoring violation (U4–U7 classes) via the live API | **`422 problem+json errorCode=ROLE_DEFINITION_INVALID`** — the new code emitted end-to-end; spec enum contains it (codegen green) |
| **I6** | a senior (25) assigns a member-level (20) role; `assignable` stub → `true` | **201** — both gates pass; both snapshots read **after** `lockTeam` |
| **I7** | a senior assigns a senior- or admin-level role | **`422 ROLE_SUBSET_VIOLATION`** (mechanism: the `≤ member` bound / cross-tier gate) — the OPA stub asserts `assignable` was **not** asked (level gate fires first) |
| **I8** | a senior assigns member-level; `assignable` stub → `false` | **`422 ROLE_SUBSET_VIOLATION`** (mechanism: subset verdict) |
| **I9** | a senior assigns member-level; the OPA stub answers 500 / times out / returns no `result` | **`422 ROLE_SUBSET_VIOLATION`** — fail-closed; no membership row written |
| **I10** | the admin tier: admin assigns level-20 (201); admin assigns admin (422, mechanism: strict `<`); **admin whose role denies `delete` assigns full `WRITE` (201 — the designed cell)**; a role snapshot with missing/non-numeric `role_level` (422, pinned semantic #5) | exactly as listed; `assignable` is never consulted for the admin path |
| **I11** | **the migrated latch race** (`MembershipConcurrencyIT`): actor demoted owner→senior in tx A while tx B (the actor granting an admin-level role) blocks on the team row | B waits on the lock, then fails the **new level gate** against the post-commit snapshot — `SubsetRuleViolationException`; the Critical-1 decide-under-protection re-proof |
| **I12** | a **custom** level-25 role bound to a user; that user calls a membership endpoint | **403** at the `team:manage` gate — `TeamRoleCapabilities` gives custom codes no `manage` (ceiling ≠ live power; the pinned no-team-verbs cell) |

## Integration — catalog (T6)

| # | Case | Expected |
|---|------|----------|
| **I13** | update with a **tags delta only**, stub allows `assign-tags`, denies `update` | **200** — the decision sequence is exactly `[<type>:assign-tags]` (no `update` asked); tags persisted |
| **I14** | update with a **content delta only**, stub allows `update` | **200** — sequence exactly `[<type>:update]` (no `assign-tags` asked); update with **both** deltas → both decisions, in order, before any write |
| **I15** | update with a tags delta, stub **denies** `assign-tags` (WRITE-no-TAG) | **403** problem+json; the entity (content *and* tags) unchanged in the DB — the deny precedes mutation |
| **I16** | create with tags, stub allows `create`, denies the type-level `assign-tags` → 403, nothing persisted; create without tags → only `[<type>:create]` asked; the empty-delta PUT → `[<type>:update]` (the conservative default); GET-one/GET-list/DELETE ask `view`/`list`/`delete` | the full sweep proven by decision sequences |

## e2e — the new matrix + the regression net (T7)

| # | Case | Expected |
|---|------|----------|
| **E1** | **deny-overrides live**: a member whose role grants `WRITE` on category, denies `delete` | PUT category → **200**; DELETE the same category → **403** (mechanism: denial subtraction) |
| **E2** | **the TAG boundary, both directions**: a WRITE-no-TAG member edits content (**200**) then submits a tags delta (**403**, mechanism: `assign-tags` second decision); a TAG-no-WRITE member relabels tags-only (**200**) then edits content (**403**, mechanism: delta-dispatched `update` denied) | all four cells through the gateway |
| **E3** | **senior delegation** via the management API with a senior token: grant a member-level role (**201**); target a senior/admin-level role (**422**); grant a role exceeding the senior's effective set (**422**) | all rejections `ROLE_SUBSET_VIOLATION`; the grant cell proves senior's `manage` ladder entry live |
| **E4** | **the admin tier**: assign below (**201**); assign a peer admin (**422**, strict `<`); **the designed cell** — an admin whose own role denies `delete` assigns full `WRITE` (**201**) | exactly as pinned in [[00-DESIGN]] §4 |
| **E5** | **the stale flat role** (direct DB INSERT of `{"catalog": ["read"]}`, level 10): its member GETs/PUTs what the row once granted | **deny everywhere** (mechanism: ∅-expansion) — the clean cut's fail-closed floor, live |
| **E6** | **ladder parity**: a `reader`-bound member (GET 200 / PUT 403 / DELETE 403); a `member`-bound one (create/update/tag 200/201) | the seeded five-tier ladder governs through the gateway |
| **E7** | **the regression net**: every existing runner green after the payload migration — `run-tests.sh`, `run-matrix.sh`, team, tag, filter, hierarchy, hierarchy-list, pagination, resource-resolution; the new matrix green **twice** (idempotent) | zero flipped cells (pinned semantic #3); any flip = stop and investigate, never re-pin silently |

## Docs (T8)

| # | Case | Expected |
|---|------|----------|
| **D1** | `docs/guides/PERMISSION-MODEL.md` exists; the six reconciled docs name the new vocabulary | grep-level presence: categories table, the two gates, the second decision, ∅-expansion, the define-tags deferral |
| **D2** | [[USER-STORIES]] G1–G4 flipped (G4 with the deferral note); [[POC-ROADMAP]] 6.5 shipped; the index table ticked | consistent with the shipped state |
| **D3** | frontmatter valid on every touched note; wikilinks resolve; the clean-room scan empty; the folder moved to `implemented/` with the Shipped banner | `scripts/planning/verify-package.sh` conventions hold post-move |

## Headline proof

Two cells justify the slice: **E2** (the TAG/WRITE boundary holding in *both* directions through
the gateway — the new vocabulary doing work flat verbs never could) and **I11 + E3/E4 together**
(delegation that is safe *under concurrency*: the level gates + the `assignable` verdict deciding
on lock-read snapshots, with the designed admin cell proving the gates are seniority — not subset —
exactly as ADR 0007 pinned).
