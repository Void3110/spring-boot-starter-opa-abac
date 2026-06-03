---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# Dynamic tag dictionary — decomposition

> The ordered work list. Each ticket is one focused commit. Rationale is in [[00-DESIGN]]; the cases are in
> [[10-QA-TEST-CASES]]. **This is the implementer's work list.**

Adds a `TagDefinition` entity to the user-service (`dev.dmitriikonovalov.example.usermgmt`), tag
assignment + a `tags_satisfied` policy rule to the catalog (`dev.dmitriikonovalov.example.catalog`), and
**one** additive, backward-compatible field-pair to `opa-abac-core`'s `RoleDefinition`. Clean-room:
original names only; the prior platform's `tag/` package is **study-only**. The
`opa-abac-spring-security` / `-spring-data` / `-starter` public APIs are **frozen**; the **only** library
change is the additive `RoleDefinition.requiredTags`/`matchMode` (T4).

Critical path **T1 → T2 → T3 → T4 → T5 → T6**. T2 (define) and T3 (assign) both need T1's entity; T4 (the
role-side requirement) and T5 (the Rego match) are the **grant** half and need T3's assigned tags to match
against. T6 is the rig + e2e + docs.

### Where each layer lands (decided — see [[00-DESIGN]] "Module placement")

```
user-service (…usermgmt):   TagDefinition entity + dictionary management API + team:define-tags
                            + internal GET /internal/tag-definitions + role-def requiredTags passthrough
catalog (…catalog):         tag assignment + validation on Category + category.rego tags_satisfied
core (opa-abac-core):       RoleDefinition += requiredTags, matchMode  (TagMatchMode enum)  — additive only
infra:                      seed demo tag defs + a tagged Category; e2e matrix; OPA reload
```

---

## Ticket 1 — Tag-definition domain (`TagDefinition`) + Liquibase + seed + read API

**Goal:** the dictionary entity exists, with global + team scope, value-type/cardinality/allowed-values,
seeded global demo keys, and read access — the foundation both management (T2) and assignment (T3) build on.

**Deliverables**
- Entity in `…usermgmt.domain`: `TagDefinition(id, key, scope, teamId?, valueType, cardinality,
  allowedValues jsonb, valuePattern?, system)`. Enums `TagScope{GLOBAL, TEAM}`,
  `TagValueType{STRING, ENUM}`, `TagCardinality{SINGLE, MULTI}`. JSONB via `@JdbcTypeCode(SqlTypes.JSON)`
  (as `RoleDefinitionEntity` does).
- Liquibase `000N-create-tag-definition.yaml`: the table + the **two partial unique indexes**
  (`uq_tag_def_global_key` on `(key) WHERE team_id IS NULL`; `uq_tag_def_team_key` on
  `(team_id, key) WHERE team_id IS NOT NULL`) via the `<sql splitStatements:true>` pattern already used for
  role codes. FK `team_id → team(id)`.
- Seed (`000N+1-seed-tag-definitions.yaml`) two **GLOBAL/system** demo keys: `sensitivity`
  (`ENUM`, `SINGLE`, `[public, internal, confidential]`) and `region` (`ENUM`, `MULTI`,
  `[emea, amer, apac]`), `system=true`, `team_id=null`, stable UUIDs/codes.
- `TagDefinitionRepository`; a read-only controller: `GET /api/v1/tag-definitions` (list global + a
  team's, by optional `teamId`), `GET …/{id}`. Hand-written mapping into the OpenAPI DTO via
  `UserMgmtMapper` (no MapStruct).
- A small `TagValueValidator` (service) — given a `TagDefinition` + a submitted value/values, returns
  valid/invalid: enum membership, regex (STRING), and cardinality (scalar vs array) checks. Used in T2/T3.

**Acceptance**
- `./gradlew :example-user-management-service:build` green; `ddl-auto: validate` boots clean against the
  new table.
- The two seeded global keys exist with `system=true`, `team_id=null`; `allowedValues` round-trips through
  JSONB.
- The partial-unique indexes hold: a second GLOBAL `sensitivity` → violation; the same `key` is allowed
  once globally **and** once per team.
- `TagValueValidator` unit tests: enum hit/miss, regex hit/miss, SINGLE-given-array / MULTI-given-scalar
  rejected.

**What NOT to touch**
- Management writes (T2); the catalog; role definitions; the rego. No `@AutoTag` machinery.

---

## Ticket 2 — Dictionary management API (define team-scoped keys; system keys immutable)

**Goal:** an owner/admin defines and edits **team-scoped** tag keys at runtime; global/system keys are
immutable; the operation is dogfood-secured.

**Deliverables**
- Endpoints (under a team): `POST /api/v1/teams/{teamId}/tag-definitions {key, valueType, cardinality,
  allowedValues?, valuePattern?}` → creates a `TEAM`-scoped definition (`teamId` set, `system=false`);
  `PUT …/{id}` update; `DELETE …/{id}`; `GET …` list this team's keys (+ globals).
- **Validation on define:** a well-formed key (kebab-case), `ENUM` requires a non-empty `allowedValues`
  (≤ cap), `STRING` may carry a `valuePattern`; reject contradictions (422).
- **System keys immutable:** update/delete where `system=true` (or `scope=GLOBAL`) → **409**.
- **Dogfooded authorization:** annotate the define/edit endpoints
  `@OpaPreAuthorize(action="team:define-tags", resourceType="'team'", resourceId="#teamId")`; extend the
  user-service `team.rego` so `team:define-tags` is allowed for `owner`/`administrator` and denied for
  `member`/`viewer` (default deny). Use `/rego-skill`; `opa test`.
- The new verb shares the `team:` prefix convention so the action verb stays a single clean token
  (mx — the verb-prefix gotcha from the team rego).

**Acceptance**
- ITs: owner/administrator create/edit/delete a team-scoped key; member/viewer → **403**; editing a global
  key → **409**; a malformed definition → **422**.
- `opa test team.rego` green: `team:define-tags` allowed for owner/admin, denied otherwise, default deny.

**What NOT to touch**
- Assignment (T3); the role `requiredTags` (T4); the catalog; `category.rego`.

---

## Ticket 3 — Tag assignment on the Category sub-resource (validated against the dictionary)

**Goal:** an owner/member with write attaches dictionary-validated tags to a Category; illegal tags are
rejected, not silently dropped.

**Deliverables**
- **user-service:** `GET /internal/tag-definitions?resourceType&resourceId` — returns the applicable
  definitions (GLOBAL keys + the keys of the team whose team-target governs that resource), reusing the
  `TeamTargetMatcher` from the resolve API. Internal-only (not gateway-fronted); cacheable.
- **catalog:** on `POST`/`PUT` Category, accept a `tags` map; before persisting, fetch the applicable
  definitions (a small `TagDefinitionClient` on the JDK `HttpClient`, **fail-closed** like
  `HttpRoleDefinitionSupplier` — but here a fetch failure must **reject the write** with a clear 503/422,
  never store an unvalidated tag), then validate each entry: known key, `valueType`, `cardinality`. Store
  valid tags in the Category's `ResourceTags` (`SINGLE` → string tag, `MULTI` → array tag). Unknown key /
  illegal value → **422** with the offending key.
- The existing `@OpaPreAuthorize(category:write)` already governs *who* may assign; **no new capability**.
- `TagDefinitionClient` unit tests with an in-process `HttpServer` stub (no WireMock), mirroring the
  Phase-4 `HttpRoleDefinitionSupplierTest`: valid-set round-trip + every fail-closed path.

**Acceptance**
- ITs (catalog, Testcontainers): assigning a legal `sensitivity=internal` + `region=[emea,amer]` persists
  into `ResourceTags` (scalar + array); an unknown key → 422; an enum miss → 422; a SINGLE-given-array →
  422; a definitions-fetch failure → the write is **rejected** (fail-closed), not persisted untagged.
- A member with `write` can assign; a viewer cannot (existing write authorization, unchanged).

**What NOT to touch**
- The role `requiredTags` (T4); the `tags_satisfied` rule (T5); the resolve-API role shape.

---

## Ticket 4 — `RoleDefinition` extension: `requiredTags` + `matchMode` (additive core change)

**Goal:** a role can carry a tag requirement; the change is additive and breaks nothing.

**Deliverables**
- **opa-abac-core:** add to `RoleDefinition` two optional components — `Map<String,List<String>>
  requiredTags` and a `TagMatchMode matchMode` (enum `ANY_OF`/`ALL_OF`, default `ANY_OF`). Defensive
  copies in the compact constructor; `@JsonInclude(NON_EMPTY/NON_NULL)` so a role without a requirement
  serializes **exactly as before** (`required_tags`/`match_mode` absent). Add the convenience constructor
  that omits them (keeps every existing caller compiling unchanged).
- **user-service role-def management (extends Phase-4 T5):** the create/update role-definition endpoint
  accepts optional `requiredTags` + `matchMode`; persist them on the `RoleDefinitionEntity` (a JSONB
  `required_tags` column + a `match_mode` string; Liquibase `000N-add-role-required-tags.yaml`). The
  resolve API (`/internal/effective-role`) returns them inside the `core.RoleDefinition` verbatim.
- **Subset-rule interaction:** document + enforce that adding `requiredTags` only *narrows* a role (it
  cannot grant more), so it is compatible with the Phase-4 subset rule (a narrower role is still a subset).
- Update `RoleDefinitionTest` (core) for the new fields + back-compat serialization; update the user-service
  role-def ITs.

**Acceptance**
- `./gradlew build` green — **all existing** core/security/starter/catalog/user-service tests pass
  unchanged (proves additivity); a role with no `requiredTags` serializes byte-for-byte as before.
- A role created with `requiredTags={sensitivity:[public,internal]}, matchMode=ALL_OF` round-trips through
  the user-service DB and the resolve API into a `core.RoleDefinition`.
- Clean-room scan clean; **no change** to `opa-abac-spring-security`/`-spring-data`/`-starter` public APIs.

**What NOT to touch**
- The `tags_satisfied` rego (T5); the catalog assignment (done in T3); the gateway policy.

---

## Ticket 5 — Rego tag match (`some in` / `every`)

**Goal:** OPA grants access only when the resource's tags satisfy the role's `requiredTags` per `matchMode`
— the layer that turns tags into authorization.

**Deliverables**
- Extend the catalog's per-type policy (at least `category.rego`, the tagged resource) so `allow` requires
  **both** the existing permission check **and** a new `tags_satisfied` rule:
  - `key_satisfied(key, acceptable)` — the resource's value(s) for `key` intersect `acceptable`
    (scalar tag → singleton set; array tag → its elements), via `some v in … ; v in acceptable`.
  - `tags_satisfied` for **ANY_OF** via `some key, acceptable in input.role_definition.required_tags`
    (existential), and for **ALL_OF** via `every key, acceptable in … { key_satisfied(...) }` (universal).
  - **Vacuous truth:** `tags_satisfied` is true when `required_tags` is absent (back-compat — roles without
    a requirement behave exactly as Phase 4).
  - **Fail-closed:** a malformed/missing `required_tags` when one is expected → `tags_satisfied` fails →
    deny; `default allow := false` unchanged.
- `category_test.rego` (via `/rego-skill`): ANY_OF hit/miss, ALL_OF hit/miss, multi-value array
  intersection, no-required-tags (vacuous allow), permission-ok-but-tags-fail → deny,
  tags-ok-but-permission-fail → deny, default deny.
- Keep the gateway `gateway.rego` coarse (two-layer model untouched); the tag match is an **app-layer**
  decision.

**Acceptance**
- `opa test` green for `category.rego` (all the above cases).
- A manual probe through the running OPA (the `ABAC-IMPLEMENTATION`-style `curl` of the per-type path)
  shows allow/deny flipping on the resource's `sensitivity`/`region` tags vs the role's `required_tags`.

**What NOT to touch**
- `core`/`security`/`starter`/user-service code; the gateway policy; batch/partial; ReBAC.

---

## Ticket 6 — e2e matrix (tag-gated allow/deny) + docs + roadmap/Mulch

**Goal:** the full loop — a catalog request allowed/denied **by tags** through the gateway, role + required
tags from the user-service, resource tags assigned via the dictionary — proven in the rig; docs tell the
story; roadmap + Mulch updated.

**Deliverables**
- **Infra/seed:** seed the demo global tag definitions; bootstrap a team-scoped role whose `requiredTags`
  gate a Category (e.g. a `regional-reader` requiring `region:[emea]`); create/seed a Category tagged
  `region=[emea]` and another tagged `region=[apac]`. Extend `deploy.sh`/compose as needed; reload the
  updated `category.rego`.
- **e2e (Postman/newman):** a matrix proving the **tag-gated** path through the gateway —
  - a member with the tag-gated role **reads the matching-tag Category → 200**;
  - the **same** member **reads the non-matching-tag Category → 403** (role permission identical; only the
    tags differ — the decisive proof that tags grant access);
  - ANY_OF vs ALL_OF demonstrated (a role requiring two keys: satisfied vs partially-satisfied);
  - a **dictionary** path: owner defines a team-scoped key → 200; a member attempts define → 403;
    assigning an illegal value → 422.
  Reuse the in-network token + collection-scope-id conventions (mx-ecc3ef, mx-05b2c1). Mint tokens
  in-network; runtime-captured ids in collection scope.
- **Docs:** a guide `docs/guides/TAG-BASED-AUTHORIZATION.md` (the three layers; global vs team scope; the
  value-type/cardinality/allowed-values model; ANY_OF/ALL_OF; the Rego match; who-manages-what; the
  dictionary-vs-source-platform improvement). Update `docs/TAG-SYSTEM.md`, `infra/README.md`,
  `docs/guides/E2E-TESTING.md`, and `POC-ROADMAP.md` (Phase 4.5 done; Phase 5 + Phase 7 next). Cross-link
  [[RESEARCH-AUTOTAG-AND-FILTERING]].
- **Move** `TAG-DICTIONARY/` → `docs/to-do/implemented/` with a "Shipped" banner.
- **Mulch:** record durable insights (the three-layer split; the global+team partial-unique reuse for tag
  keys; value-type/cardinality on the row; the additive `RoleDefinition.requiredTags`/`matchMode`; the
  `some in`/`every` ANY_OF/ALL_OF Rego match; fail-closed assignment validation). `ml sync`
  (`.mulch`-only); `ml doctor` clean.

**Acceptance**
- Rig up → the e2e matrix green (tag-match read 200 / tag-mismatch read 403 for the same role; ANY_OF vs
  ALL_OF; the dictionary define/assign matrix), stable across reruns.
- `./gradlew build` green; `opa test` green; docs/roadmap/Mulch + the `STATUS-0N.md` notes updated;
  **clean-room scan clean**. **No push.**

**What NOT to touch**
- The library public APIs beyond T4's additive field; batch/partial (Phase 5); ReBAC (Phase 7); `@AutoTag`.

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green: all library modules + **both** example apps + OpenAPI codegen + ITs.
- The **only** library public-API change is the **additive, backward-compatible**
  `RoleDefinition.requiredTags`/`matchMode` (T4) — every pre-existing test passes unchanged; a role with no
  requirement serializes exactly as before.
- **Fail-closed** holds: an unknown/illegal assigned tag → 422 (never silently stored); a
  definitions-fetch failure → the write is rejected; a malformed `required_tags` → `tags_satisfied` fails →
  deny; OPA `default allow := false`.
- **The dictionary is genuinely dynamic:** a team-scoped key is created at runtime and immediately governs
  assignment + (via a role's `requiredTags`) decisions — no redeploy. Global/system keys are immutable.
- **The decisive demo holds:** two Categories, identical role permission, different tags → one allowed, one
  denied — proving tags (not just `permissions`) drive the decision, matched **in Rego**.
- `ddl-auto: validate` boots clean for the user-service (Liquibase owns the new table + the role-def
  `required_tags` column).
- e2e matrix green through the gateway; docs (the new guide + roadmap + `TAG-SYSTEM.md`), Mulch, and the
  `STATUS-0N.md` notes updated; clean-room scan clean. **Nothing pushed.**
