---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# Permission categories + delegation — decomposition (Phase 6.5)

> The ordered work list for [[PERMISSION-CATEGORIES]] (Phase 6.5 of [[POC-ROADMAP]]). Eight tickets,
> one focused commit each. Design: [[00-DESIGN]] (the ten settled forks — §2/§7; do not reopen). QA:
> [[10-QA-TEST-CASES]]. Run via the [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]. Pinned by ADR
> [[0007-coarse-grained-permission-categories|0007]] + its Phase-6.5 implementation addendum.
>
> **Packages.** Library: `dev.dmitriikonovalov.opaabac.core` (one additive wire field — T1, the
> flagged build-breaker; **everything else in `opa-abac-*` untouched**). Examples:
> `dev.dmitriikonovalov.example.usermgmt.*` (T3–T5) and `dev.dmitriikonovalov.example.catalog.*`
> (T6). Policies: `infra/opa/policies/` (T2, T5).
>
> **The clean cut (read first).** This slice **consciously waives the additive-only doctrine**
> ([[00-DESIGN]] §2.3; the ADR addendum): the starter is unpublished with zero adopters, so seeds,
> annotations, policies, IT fixtures, and e2e payloads migrate **non-additively in one slice**. The
> fail-closed replacement for compatibility is: **an unknown/stale permission token expands to ∅ and
> therefore denies** — never decides. Mid-branch incoherence (e.g. the catalog still sending
> `category:read` after T2 rewrites the policies) is expected and resolved by T6/T7 on the same
> branch; the rig only has to be coherent at the T7 e2e gate.

## Critical path

```
T1 ──► T3 ──► T4 ──► T5 ──┐
T2 ───────────┬───► T5    ├──► T7 ──► T8
              └───► T6 ───┘
```

- **T1 → T3 → T4 → T5** are strictly sequential (the user-mgmt chain: wire field → schema/seeds →
  authoring contract → assignment gates).
- **T2 (policies) is independent of T1/T3/T4** and can land first or in parallel; it must land
  **before T5** (`role.rego` imports the shared `permissions.rego`) and **before T6** (the catalog
  sweep targets the new vocabulary).
- **T6 (catalog) is independent of T3–T5** (different service) but follows T2.
- **T7 (e2e) needs everything**; T8 closes the record.
- **There is no independently-landable subset.** The clean cut makes the slice atomic: nothing
  before T7 may merge alone (the branch ships whole, or not at all). This replaces the usual
  standalone-value note — stated so the run doesn't look for one.

## Pinned decomposition semantics (so the run never stops to ask)

The 00-DESIGN pins the forks; these five pin the contract details the design left implicit
(the recurring planning-gap class — settle them here, not mid-run):

1. **Error contract.** Authoring-contract violations (bad `roleLevel`, non-category token, ceiling
   exceeded, denial-not-subset) → **`422`** with a **new app code `ROLE_DEFINITION_INVALID`**
   (`UserMgmtErrorCode`, mirroring `TAG_DEFINITION_INVALID`); the **closed `errorCode` enum in
   `user-mgmt-api.yaml` gains it in the same commit** (the enum-sweep lesson). All assignment-gate
   rejections — cross-tier level, senior's `≤ member` bound, the `assignable` subset verdict, **and
   an OPA error/timeout during the verdict** — keep the existing **`422 ROLE_SUBSET_VIOLATION`**
   contract (one rejection shape; an OPA outage is deliberately indistinguishable from
   "not assignable" — fail-closed by indistinguishability). No other enum change.
2. **The TAG/WRITE boundary on update is delta-aware gate dispatch.** A static
   `@OpaPreAuthorize("<type>:update")` cannot satisfy the pinned cell "TAG-without-WRITE relabels
   but never edits" ([[00-DESIGN]] §4). So the three **update** endpoints move their authorization
   from one static annotation to **conditional calls on an annotated gate bean**
   (`TagDecisionGate`, T6): content delta → the `<type>:update` decision; tags delta → the
   `<type>:assign-tags` decision; both → both decisions; **empty delta → `<type>:update`**
   (conservative default — a no-op PUT by a TAG-only holder answers 403; fail-closed). **Create
   keeps its static `<type>:create` annotation** + a conditional **type-level** `assign-tags`
   decision when the request carries tags (no instance exists yet — AWS tag-on-create semantics).
   The gate bean's methods carry `@OpaPreAuthorize`, so every decision still flows through the
   manager seam and reuses the 5.97 resolved-instance request cache — **zero library change**.
3. **The nine-runner payload-migration rule** (T7, mechanical): `["read"]` → `["READ"]`;
   `["read","write"]` → `["READ","WRITE","TAG"]` (pre-6.5 `write` implied tag-setting — the same
   principle as the pinned realm-fallback mapping, preserving each matrix's reach exactly); `{}`
   stays `{}`; every `custom-roles` bootstrap POST gains `roleLevel` (readers → `10`,
   writer/curator/editor roles → `20`). `requiredTags`/`matchMode` unchanged. The rule **preserves
   every pinned cell outcome** — if a cell flips, stop and investigate before touching the cell.
4. **E5's stale flat role is seeded by direct DB INSERT** in the new runner (the authoring API
   now rejects flat tokens by design — the bypass is the point: the cell proves a stale **stored**
   row decides nothing). The INSERT mirrors the seed changelog's row shape.
5. **A missing/non-numeric `role_level` on either snapshot at assignment time → reject**
   (`ROLE_SUBSET_VIOLATION`). The level gate never treats an unreadable level as 0-and-pass or as
   a wildcard; no level, no assignment (fail-closed).

---

## T1 — Core: `RoleDefinition` gains `deniedActions` (the flagged build-breaker)

**Goal.** The wire record carries deny-overrides; absent → empty; a denial-free role serializes
byte-for-byte as today.

**Deliverables.**
- `opa-abac-core`, `dev.dmitriikonovalov.opaabac.core.RoleDefinition`: new component
  `Map<String, List<String>> deniedActions` with `@JsonProperty("denied_actions")` +
  `@JsonInclude(NON_EMPTY)`, compact-constructor null-normalization to `Map.of()` (mirror
  `requiredTags`' treatment exactly). Javadoc: type → denied fine actions, subtracted **after**
  category expansion, ADR 0007 deny-overrides.
- Convenience constructors (the 3-arg and the 5-arg tag form) delegate with an empty map — their
  signatures unchanged.
- **The build-breaker, contained here (DATA-FILTERING T1 model):** widening the canonical
  constructor breaks every positional `new RoleDefinition(…)` call site. Sweep
  `grep -rn "new RoleDefinition(" opa-abac-* example-*` and update **every** site + test stub **in
  this same commit**, listing them in `STATUS-01.md`.

**Acceptance.** QA **U1–U3**. `./gradlew build` green (all modules). U1 is the headline: a role
without denials serializes **string-equal** to the pre-6.5 form (existing serialization tests
unchanged-green); JSON missing the field deserializes to an empty map.

**What NOT to touch.** `OpaClient` / `AbacContext` / `RoleDefinitionSupplier` signatures; every
other core type. `opa-abac-core` stays Spring-free. No policy, no example-service logic (their
constructors only widen mechanically here).

---

## T2 — Policies: the expansion table, the shared `permissions.rego`, and the per-type clean cut

**Goal.** The four categories expand to fine actions in OPA `data`, one shared function does
expand-minus-deny, and the three per-type policies decide the new vocabulary — with the rewritten
`opa test` suites and the PE-fold proof.

**Deliverables.** In `infra/opa/policies/`:
- `permission_categories.json` — mounts at `data.permission_categories` (follow the colocated
  data-file pattern of `category_inheritable.json`):
  `READ → [view, list]` · `WRITE → [create, update, delete]` · `TAG → [define-tags, assign-tags]`
  · `GRANT → [assign-roles]`.
- `permissions.rego` (package `permissions`) — the **one runtime home** of the effective-set math:
  - `effective_actions(role_def, type)` = union of `data.permission_categories[cat]` for each
    category token granted for `type`, **minus** `role_def.denied_actions[type]`. An unknown/stale
    token contributes **nothing** (∅-expansion — the clean cut's fail-closed floor). **Wildcard
    lookup lives here**: tokens come from `role_def.permissions[type]`, falling back to
    `role_def.permissions["*"]` when the type key is absent (the decision side gets concrete keys
    from the resolve API's wildcard expansion and is unaffected; the T5 `assignable` snapshots are
    raw rows and need it).
  - `effective_from_categories(cats)` — expansion of a literal category set (the realm fallback's
    helper; no denials apply — realm roles carry none).
- Migrate `catalog.rego`, `category.rego`, `product.rego` to the new vocabulary:
  - `direct_grant`: `verb in permissions.effective_actions(input.role_definition, type)` ∧
    `tags_satisfied` (structure otherwise unchanged).
  - `inherited_grant`: membership through `effective_actions` on the **ancestor** type; the
    inheritable-data lookup and deny-overrides (`abac_deny`) untouched.
  - The realm fallback: `catalog-viewer → effective_from_categories({"READ"})`,
    `catalog-editor → effective_from_categories({"READ","WRITE","TAG"})` — same reach as today,
    now through the table.
  - `filter`: `"list" in effective_actions(…)` (role-def-only — **no fallback**, as today); the
    type-level list-gate clause and `bulk` migrate to the same vocabulary.
- Rewrite the test suites at the new vocabulary: `catalog_test.rego` / `category_test.rego` /
  `product_test.rego` (fixtures become category tokens + fine verbs; **every pre-existing
  behavioral cell is preserved, re-expressed**, incl. hierarchy/deny/tag/filter cells) + new
  `permissions_test.rego` (the algebra: expansion, denial subtraction, unknown-token ∅, wildcard
  fallback, empty permissions).
- The **PE-fold harness** (a **T2 acceptance, not a discovery** — [[00-DESIGN]] §6): the exact
  `opa eval --partial` command in QA **P10**, proving the expansion folds over known
  input+data and the `filter` residual keeps the 5.x shape (tag conditions over
  `input.resource…` only).

**Acceptance.** QA **P1–P12**. `opa test infra/opa/policies/` green; the P10 fold command's
residual shape verified and pasted into `STATUS-02.md`.

**What NOT to touch.** `team.rego` (decision 1 — out of scope) and `gateway.rego` (verb-free,
verified — it returns allow unconditionally for authenticated routes). The
`*_inheritable.json` files. Deny-overrides/`abac_deny` semantics. The `filter` rule keeps **no
subject-roles fallback**. Zero Java in this ticket.

---

## T3 — user-mgmt: schema + the seed migration + the resolve wire

**Goal.** `role_definition` carries `denied_actions`, the five-tier seed ladder exists (senior 25
in, `viewer`→`reader`), and the resolve API serves both to the catalog.

**Deliverables.** In `example-user-management-service`:
- Liquibase `db/changelog/changes/0006-permission-categories.yaml` (one changelog, multiple
  changesets):
  1. `denied_actions` jsonb, default `'{}'`, non-null, on `role_definition`.
  2. Seed rewrite (`UPDATE`s of the four system rows + one `INSERT`): `owner` (id `…0001`, level
     40 kept) and `administrator` (`…0002`, 30) → permissions `{"*": ["READ","WRITE","TAG","GRANT"]}`;
     **new `senior`** row (id `00000000-0000-0000-0000-000000000005`, level 25,
     `{"*": ["READ","WRITE","TAG"]}`); `member` (`…0003`, 20) → `{"*": ["READ","WRITE","TAG"]}`;
     `viewer` (`…0004`) → **code `reader`**, level 10, `{"*": ["READ"]}`. All rows
     `denied_actions: '{}'`.
  3. The defensive sweep `UPDATE` for stray lowercase tokens in non-system rows (dev DBs persist
     across runs; a stale row must not linger half-migrated — and what it can't fix still lands on
     the ∅-expansion floor).
- `domain/RoleDefinitionEntity`: `deniedActions` jsonb field mirroring `permissions`' mapping.
- The resolve flow carries denials: `EffectiveRoleService.resourceRole(…)` passes
  `expandWildcard(role.getDeniedActions(), targetType)` into the (T1-widened) core
  `RoleDefinition` — **wildcard expansion applies to denials exactly as to grants**;
  `managementRole(…)` keeps an empty denial map (team verbs are out of scope).
- `web/UserMgmtMapper` + `user-mgmt-api.yaml`: the public `RoleDefinition` **response** schema
  gains `roleLevel` (read from `attributes.role_level`) + `deniedActions` (the G1 round-trip
  lens). Internal resolve responses serialize the core record (snake_case `denied_actions`,
  omitted when empty — proven by IT).
- **Build-breaker, this commit:** every test/fixture referencing role code `viewer` or asserting
  the old seed permissions (`MembershipManagementIT`, `EffectiveRoleResolveIT`,
  `RoleDefinitionManagementIT`, …) migrates to `reader`/category tokens here. List them in
  `STATUS-03.md`.

**Acceptance.** QA **I1–I3**. `./gradlew :example-user-management-service:test` green against real
Postgres — the seed cells (senior row present at 25; `reader` present, `viewer` gone; owner/admin
carry all four categories; `denied_actions = {}` everywhere), the `ddl-auto: validate` boot, and
the resolve-wire cell (`denied_actions` present when non-empty, omitted when empty).

**What NOT to touch.** Authoring validation (T4) and the assignment gates / `TeamRoleCapabilities`
(T5) — flat tokens still **store** unvalidated in this ticket. The Keycloak realm: the `viewer`
**user** and the `catalog-viewer`/`catalog-editor` **realm roles** are unrelated realm artifacts —
zero realm/infra edits. The membership FK is by role **id** — the rename touches no membership row.

---

## T4 — user-mgmt: the authoring contract (level ceiling, category tokens, strict denials)

**Goal.** A role is authored by picking a level; the API accepts only category tokens within the
level's ceiling, denials must subtract from actual grants, and the vestigial author-subset check
is gone.

**Deliverables.** In `example-user-management-service`:
- `service/PermissionCategories` — the app-side validation table: the four category tokens, the
  expansion map, and the per-level ceilings (`10 → {READ}` · `20/25 → {READ,WRITE,TAG}` ·
  `30 → {READ,WRITE,TAG,GRANT}`). **The runtime decision home stays OPA `data`** — this constant
  exists for 422-time validation only, and a unit test (U9) pins parity with
  `infra/opa/policies/permission_categories.json` (read via the repo-relative path from the module,
  as the rego-fixture convention does) so drift breaks the build.
- `service/RoleDefinitionService.create/update` validation (before save, after the existing
  code/uniqueness checks), each violation → `RoleDefinitionInvalidException` → **`422
  ROLE_DEFINITION_INVALID`** (pinned semantic #1):
  1. `roleLevel` required, ∈ `{10, 20, 25, 30}`.
  2. Every `permissions` value token ∈ the four categories (retires flat verbs at the API
     boundary).
  3. Granted categories ⊆ `ceiling(roleLevel)` (`GRANT` only at 30).
  4. **Strict denial validation**: for every type key, `denied_actions[type] ⊆
     expand(granted categories for that type)` — denying something not granted is rejected.
  5. The explicit `roleLevel` is written into `attributes.role_level` (single source — an
     attributes-supplied `role_level` is overwritten; note it in the OpenAPI description).
- **Drop the authoring-time author-subset check**: remove the
  `subsetGuard.requireWithinActorPermissions(…)` calls from create/update (vestigial under
  owner-only authoring — the ceiling is the real bound; [[00-DESIGN]] §2.8). The R-test pinning it
  flips to a ceiling cell — name it in `STATUS-04.md`.
- `web` + `user-mgmt-api.yaml`: `RoleDefinitionRequest` (required gains `roleLevel`) and
  `RoleDefinitionUpdate` (same) gain `roleLevel` (integer enum) + `deniedActions`;
  `ApiExceptionHandler` maps the new exception; `UserMgmtErrorCode.ROLE_DEFINITION_INVALID` +
  the **closed `errorCode` enum sweep** (same commit).
- `web/InternalBootstrapController.EnsureCustomRole` gains `roleLevel` (required) +
  `deniedActions` (optional) and **flows through the same service validation** (no bypass).
  *(Its only callers are the e2e runners — they migrate in T7; named there.)*

**Acceptance.** QA **U4–U9, I4–I5**. `./gradlew :example-user-management-service:test` green: the
authoring round-trip (senior-level role with a denial, read back with `roleLevel` +
`deniedActions`), each 422 cell live as `problem+json errorCode=ROLE_DEFINITION_INVALID`, and
codegen + `ddl-auto: validate` clean.

**What NOT to touch.** The owner-only endpoint gate (`team:define-roles` — control-plane, out of
scope). System-role immutability. `MembershipService` / `SubsetGuard.requireAssignableByActor`
(T5 replaces it — not this ticket). The delete + in-method-flush 409 path.

---

## T5 — user-mgmt: the hybrid assignment gates + `data.role.assignable`

**Goal.** Assignment is gated by Java level compares under the team-row lock plus, at senior only,
the OPA subset-on-effective verdict — replacing the always-on 2-D subset check, with the Critical-1
race invariant re-proven.

**Deliverables.**
- `infra/opa/policies/role.rego` (package `role`) + `role_test.rego`: `assignable` — input
  `{actor_role, candidate_role}` (the two **raw row snapshots**: `permissions`, `denied_actions`);
  **`default assignable := false`**; true iff for **every** type key in the candidate's
  `permissions`, `permissions.effective_actions(candidate_role, type) ⊆
  permissions.effective_actions(actor_role, type)` (the shared function — wildcard-aware per T2).
  The truth table is QA **P13**.
- `service/RoleAssignableClient` (new, app-side — `OpaClient` has no arbitrary-entrypoint call and
  the **library stays untouched**): `boolean assignable(RoleDefinitionEntity actor,
  RoleDefinitionEntity candidate)` — `RestClient` POST to `{opa-base-url}/v1/data/role/assignable`
  with `{"input": {"actor_role": …, "candidate_role": …}}`, reusing the starter's configured OPA
  base-url property; short timeout; **any error / timeout / non-2xx / missing `result` → `false`**
  (fail-closed). *(Consumer: `MembershipService`; non-happy path: I9.)*
- `service/MembershipService.addMember/changeRole` — inside the existing `lockTeam(teamId)`
  transaction (decide-under-protection; B3), replace `subsetGuard.requireAssignableByActor(…)`
  with the gate pair over **lock-read snapshots** (the actor's membership role + the candidate
  role, both read after the lock):
  1. **Cross-tier (everyone):** `actorLevel > candidateLevel`, levels from
     `attributes.role_level`; missing/non-numeric on either side → reject (pinned semantic #5).
  2. **At senior (`actorLevel == 25`) additionally:** `candidateLevel ≤ 20` **and**
     `roleAssignableClient.assignable(actorSnapshot, candidateSnapshot)` — `false` (incl. OPA
     failure) → reject.
  - Every rejection throws `SubsetRuleViolationException` (the existing **`422
    ROLE_SUBSET_VIOLATION`** contract — pinned semantic #1). `requireTargetIsNotTheOwner` and
    `TeamService.transferOwnership` unchanged.
- `service/TeamRoleCapabilities`: `senior → ["read", "manage"]` (the coarse `manage` entry — its
  *constraint* lives in the gates above, not the verb); the `viewer` key renames to `reader`;
  the custom-role default stays `["read"]` — **a custom level-25 role has senior's ceiling but no
  live assign power** (pinned by I12).
- `SubsetGuard` + its `EffectiveRoleService` support methods: **delete** once both call sites are
  gone (T4 removed the authoring call; this ticket removes the assignment call) — dead code does
  not linger. `SubsetRuleViolationException` **stays** (it is the rejection contract).
- ITs (real Postgres; the in-process `HttpServer` OPA stub plays `assignable` — programmable
  verdicts; the **real** subset math is `opa test`'s job in P13): the gate matrix I6–I10, the
  OPA-down rejection I9, the custom-role pin I12, and **`MembershipConcurrencyIT` migrated** — the
  latch-based demote-vs-grant race re-proven under the new gates (the demoted actor's racing grant
  blocks on the team row, then fails the **new** level gate; Critical-1 re-proof, I11).

**Acceptance.** QA **P13, I6–I12**. `opa test infra/opa/policies/` green (the `assignable` table);
`./gradlew :example-user-management-service:test` green.

**What NOT to touch.** `team.rego` — senior's `manage` arrives via `TeamRoleCapabilities` feeding
`permissions["team"]`, **not** a policy edit. The membership endpoints' `team:manage` annotations
(control-plane, out of scope). The lock itself (`lockTeam` stays first; the gates only read
post-lock state — Rules 1–2 of `CONCURRENCY-AND-LOCKING.md`). The library modules.

---

## T6 — catalog: the action-string sweep + the conditional `assign-tags` second decision

**Goal.** Every catalog gate speaks the fine vocabulary, and the TAG/WRITE boundary is live in
both directions via delta-aware gate dispatch — with zero library change.

**Deliverables.** In `example-catalog-management-service`:
- The annotation sweep ([[00-DESIGN]] §3) across `web/CatalogController`, `web/CategoryController`,
  `web/ProductController` (15 annotations): GET-one → `<type>:view`; GET-list → `<type>:list`;
  POST → `<type>:create`; DELETE → `<type>:delete`. **PUT endpoints drop their static annotation**
  (pinned semantic #2) in favor of gate dispatch below.
- The programmatic action-string sweep: `grep -rn '":read"\|":write"\|:read\b\|:write\b'` across
  the module (incl. `CategoryListAuthorizer` and every list-path `AbacContext` builder) — list
  verbs become `<type>:list`.
- `config/TagDecisionGate` (new bean — the **manager seam reused**, so the 5.97 resolution +
  request cache apply unchanged): per-type annotated methods —
  `requireUpdate(UUID id)` with `@OpaPreAuthorize(action = "<type>:update", resourceType =
  "'<type>'", resourceId = "#id")`, `requireAssignTags(UUID id)` (`<type>:assign-tags`, id'd), and
  `requireAssignTagsForCreate()` (`<type>:assign-tags`, type-level) — for catalog, category, and
  product (however grouped, every method carries its own static annotation; Spring AOP requires
  the calls cross a bean boundary — never self-invocation).
- The three **update** handlers: compute the deltas first — content delta (any non-tag field
  differs from the loaded entity) and tags delta (the validated new tags map ≠ the entity's
  current tags) — then dispatch: content → `requireUpdate(id)`; tags → `requireAssignTags(id)`;
  both → both; **neither → `requireUpdate(id)`** (the conservative default). All decisions run
  **before any mutation**; the 5.97 `VersionGuard` flow on the gate snapshot is unchanged.
- The three **create** handlers: keep `<type>:create` static; iff the validated request tags map
  is non-empty → `requireAssignTagsForCreate()` before persisting.
- `TagAssignmentService.validateAndBuild` (dictionary legality, 422) **still runs** — it is
  validation, not authorization; both happen.
- ITs (`ResourceResolutionGateIT` pattern — the programmable per-action `OpaClient` stub):
  I13–I16, asserting the **decision sequence** (which actions were asked) and that a denied
  second decision leaves the entity untouched.

**Acceptance.** QA **I13–I16**. `./gradlew build` green (all modules — the catalog ITs and every
older catalog IT re-expressed at the new verbs in this commit; list them in `STATUS-06.md`).

**What NOT to touch.** The library modules (the gate bean is plain example-app code). Pagination /
`AbacQueryService` / the four `findAuthorized` paths (only their action **strings** change).
`SecurityConfig`, the OpenAPI specs (no shape change — the REST surface is unchanged), user-mgmt.
The tag-validation 422 path.

---

## T7 — e2e: the permission-categories matrix (fixture `9999…`) + the suite-wide payload migration

**Goal.** The behavior matrix is proven through the gateway on a dedicated fixture, and the whole
existing suite — payloads migrated by the mechanical rule — stays green as the regression net.

**Deliverables.** In `scripts/postman/`:
- `run-permission-categories-matrix.sh` + `permission-categories-matrix.postman_collection.json`:
  - Fixture catalog **`99999999-9999-9999-9999-999999999999`** (registry row added; the README
    table is the source of truth). Team on the `9999…` target; subjects/roles bootstrapped via the
    internal API **with `roleLevel`/`deniedActions`**; catalog rows seeded with `path` (ltree) as
    the team/tag/filter runners do.
  - **Runner hygiene** (the retro checklist): `set -euo pipefail`; in-network token mint;
    **restarts OPA + 30×1s health-polls** (mirror `run-resource-resolution-matrix.sh` lines
    80–89 — this slice edits every policy); idempotent re-run (bootstrap upserts tolerate
    existing rows); `bash -n` + `jq .` clean.
  - The cells (each asserts the **decision**, QA **E1–E6**): E1 deny-overrides (`WRITE` minus
    `delete`: update 200 / delete 403); E2 the TAG boundary **both directions** (WRITE-no-TAG:
    content-only 200, tags-delta 403; TAG-no-WRITE: tags-only 200, content 403); E3 senior
    delegation through the management API with a senior token (member-level grant 201; senior/admin
    target 422; subset-violating 422 — all `ROLE_SUBSET_VIOLATION`); E4 the admin tier (below 201;
    peer admin 422; **the designed cell**: admin-with-`delete`-denied assigns full `WRITE` → 201);
    E5 the stale flat role (direct DB INSERT — pinned semantic #4) decides nothing (deny on
    everything it once granted); E6 ladder parity (a `reader`-bound member reads but can't write;
    a `member`-bound one creates/updates/tags).
- **The suite-wide migration**: every `custom-roles` payload in the nine existing runners migrates
  by **pinned semantic #3** (the exact payload table is in the scout appendix of
  `STATUS-07.md` when done); `run-hierarchy-matrix.sh`'s `no-access` role keeps `{}` (+ level 10).
  Collections are wire-shape-agnostic on permissions (verified) — payloads only live in the
  runners.
- `scripts/postman/README.md`: the new matrix row + the `9999…` registry row.

**Acceptance.** Rig rebuilt from this branch (`./deploy.sh build` **and** the usermgmt image —
`deploy.sh build` only rebuilds the catalog image; build usermgmt explicitly as the H-run did),
`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; then: the new matrix green
**twice** (idempotency), and **every existing runner green** (`run-tests.sh`, matrix, team, tag,
filter, hierarchy, hierarchy-list, pagination, resource-resolution) — QA **E7**. Any flipped cell
in an existing matrix = stop and investigate (pinned semantic #3 says none should).

**What NOT to touch.** Existing matrices' pinned row counts and cell outcomes. Shared fixtures
(`1111…`–`8889…`) — the new matrix touches only `9999…`. The gateway config and realm export.

---

## T8 — docs + slice record: the guide, the reconciliations, stories/roadmap/index, Mulch, folder move

**Goal.** The permission model is documented as a guide, every doc that taught the flat vocabulary
is reconciled, and the slice record closes.

**Deliverables.**
- **New guide `docs/guides/PERMISSION-MODEL.md`** (named to avoid the wikilink clash with the
  [[PERMISSION-CATEGORIES]] index): the four categories + expansion table (and that it lives in
  OPA `data`), deny-overrides, the five-tier ladder + ceilings, the authoring contract (incl. the
  422 cells), the two assignment gates + `data.role.assignable` (and the OPA-failure-rejects
  posture), the delta-aware `assign-tags` second decision, the ∅-expansion fail-closed floor, and
  the **`define-tags` enforcement-deferral note** (ships in the math; the dictionary endpoints
  keep `team:define-tags` until the control-plane slice).
- Reconcile: [[ABAC-AUTHORIZATION]] (the vocabulary + example contexts), [[TEAM-BASED-AUTHORIZATION]]
  (the assignment-gate section replaces the always-on subset rule; the ladder gains senior),
  [[TAG-BASED-AUTHORIZATION]] (assign-tags as a decision; define-tags deferral),
  [[PARTIAL-EVALUATION-FILTERING]] (`filter` = `"list" ∈ effective`),
  [[HIERARCHICAL-AUTHORIZATION]] (verb examples), `docs/guides/E2E-TESTING.md` +
  `infra/README.md` (the new matrix + data file + the migrated payload shapes).
- [[USER-STORIES]]: flip **G1, G2, G3** ✅; **G4** ✅ with the define-tags-deferral note inline.
  [[POC-ROADMAP]]: 6.5 → shipped; next = Phase 6 (the 6.7 row already exists).
- The [[PERMISSION-CATEGORIES]] index: status table ticked through T8, banner → shipped.
- Mulch: record the durable insights (the ∅-expansion clean-cut pattern; the hybrid-gate shape +
  app-side `assignable` client; the delta-aware gate dispatch; the payload-migration rule) —
  **`git restore --staged .` before `ml sync`**.
- `git mv docs/to-do/planning/PERMISSION-CATEGORIES docs/to-do/implemented/PERMISSION-CATEGORIES`,
  index frontmatter → `status/done`, past-tense **Shipped** banner.

**Acceptance.** QA **D1–D3**. Frontmatter valid on every touched note; wikilinks resolve;
clean-room scan clean. **No push.**

**What NOT to touch.** ADR 0007's body (immutable — the addendum already records the slice
decisions). ADR 0003/0004/0006. `CLAUDE.md` unless a build/run step genuinely changed.

---

## Cross-cutting acceptance

- `./gradlew build` green throughout; `opa test infra/opa/policies/` green; **Testcontainers real
  Postgres** (never H2) for every IT; the new matrix + the **entire existing newman suite** green
  on a rebuilt rig (the clean cut's regression net).
- **Fail-closed, every shape:** an unknown/stale permission token expands to **∅ = deny**; a
  missing/non-numeric `role_level` at assignment time **rejects**; an OPA error/timeout during
  `assignable` **rejects**; the `filter` rule keeps no subject-roles fallback; a denied
  second decision leaves the entity untouched; denials only ever **narrow**.
- **The waived invariant, bounded:** non-additive change is licensed **only** for the migration
  surfaces (seeds, annotations, policies, fixtures, payloads, the authoring API). The library
  boundary still holds: `opa-abac-core` stays Spring-free; T1's record field is the **only**
  `opa-abac-*` change; `AbacContext`, the manager, the cache, partial-eval machinery, and
  `team.rego` are untouched.
- **Decide-under-protection holds:** every assignment decision (both gates, the OPA verdict's
  snapshots) reads state **after** `lockTeam` in the same transaction (Rules 1–2); the latch IT
  re-proves the Critical-1 invariant under the new gates.
- Negative cells name **which deny mechanism** they pin (∅-expansion vs level gate vs subset vs
  denial-subtraction vs no-role) — the stale-cell lesson.
- Clean-room throughout. One focused commit per ticket, identity
  `Void3110 <void31102025@gmail.com>`, **no push**.

## Related

[[PERMISSION-CATEGORIES]] (index) · [[00-DESIGN]] (the ten forks + behavior matrix) ·
[[10-QA-TEST-CASES]] · ADR [[0007-coarse-grained-permission-categories|0007]] (+ addendum) ·
[[USER-STORIES]] Epic G · [[POC-ROADMAP]] · [[TEAM-BASED-AUTHORIZATION]] ·
[[PARTIAL-EVALUATION-FILTERING]] · [[ATTRIBUTE-RICH-PRE-AUTHORIZATION]] (the manager seam +
request cache the second decision reuses).
