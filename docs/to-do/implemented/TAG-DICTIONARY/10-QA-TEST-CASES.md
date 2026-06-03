---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# Dynamic tag dictionary — QA test cases

> The concrete cases the unit + integration + policy + e2e work in [[01-DECOMPOSITION]] must satisfy.
> Grouped by layer/ticket. Each row names a check; the implementer turns it into a test or a manual step.
> ITs run against **real Postgres via Testcontainers** (never H2), like both example apps.

## Tag-definition domain + seed — ticket 1

| # | Case | Expected |
|---|------|----------|
| D1 | Liquibase applies `tag_definition` + the two partial-unique indexes | schema present; `ddl-auto: validate` boots clean. |
| D2 | Global system keys seeded | `sensitivity` (ENUM/SINGLE/[public,internal,confidential]) and `region` (ENUM/MULTI/[emea,amer,apac]) exist with `system=true`, `team_id=null`, stable codes. |
| D3 | `allowedValues` JSONB round-trip | the enum sets persist + reload intact. |
| D4 | Partial-unique: global key | a second GLOBAL `sensitivity` → constraint violation. |
| D5 | Partial-unique: team key | the same `key` is allowed once globally **and** once per team; two TEAM `region` for the *same* team → violation. |
| D6 | `TagValueValidator` — ENUM | value in `allowedValues` → valid; not in set → invalid. |
| D7 | `TagValueValidator` — STRING + regex | matches `valuePattern` → valid; mismatch → invalid; no pattern → any string valid. |
| D8 | `TagValueValidator` — cardinality | SINGLE given an array → invalid; MULTI given a scalar → invalid (or normalized — per the documented rule). |
| D9 | Read API | `GET /api/v1/tag-definitions` lists globals (+ a team's by `teamId`); `GET …/{id}` returns one. |

## Dictionary management API — ticket 2

| # | Case | Expected |
|---|------|----------|
| G1 | Owner defines a team-scoped key | 201; `scope=TEAM`, `teamId` set, `system=false`; appears in the team's list. |
| G2 | Administrator defines a team-scoped key | allowed (management capability). |
| G3 | Member/viewer attempts define | **403** (`@OpaPreAuthorize(team:define-tags)` deny). |
| G4 | Edit/delete a global/system key | **409** (immutable). |
| G5 | Owner edits/deletes a team-scoped key | succeeds. |
| G6 | Malformed definition | ENUM with empty `allowedValues` → **422**; bad key format → **422**; `allowedValues` over cap → **422**. |
| P1 | `opa test team.rego` | `team:define-tags` allowed for owner/administrator; denied for member/viewer; default deny. |

## Tag assignment on Category — ticket 3

| # | Case | Expected |
|---|------|----------|
| A1 | Assign legal tags | `sensitivity=internal` + `region=[emea,amer]` persist into the Category's `ResourceTags` (scalar + array). |
| A2 | Unknown key | a key with no applicable definition → **422** naming the offending key (never silently dropped). |
| A3 | Enum miss | `sensitivity=secret` (not in the set) → **422**. |
| A4 | Cardinality mismatch | `sensitivity=[a,b]` (SINGLE key, array value) → **422**. |
| A5 | Team-scoped key applies | a key defined for the governing team validates for that team's Category; a *different* team's key does not apply. |
| A6 | Definitions-fetch failure | the `TagDefinitionClient` failing (500/timeout/refused/malformed) → the write is **rejected** (503/422), nothing persisted untagged (fail-closed). |
| A7 | Assignment authorization | a member with `write` may assign; a `viewer` → 403 (existing write authorization, unchanged — no new capability). |
| H1 | `TagDefinitionClient` round-trip (stub `HttpServer`) | a `200 {definitions}` parses into the applicable set; the request URL carries `resourceType`/`resourceId`. |
| H2 | `TagDefinitionClient` fail-closed | 500 / timeout / refused / malformed → surfaced as a fetch failure (never an empty "all-allowed" set). |

## `RoleDefinition` extension — ticket 4

| # | Case | Expected |
|---|------|----------|
| C-core1 | Back-compat serialization | a `RoleDefinition` with no `requiredTags` serializes with `required_tags`/`match_mode` **absent** (byte-for-byte as before). |
| C-core2 | New fields round-trip | `requiredTags={sensitivity:[public,internal]}, matchMode=ALL_OF` serializes/deserializes intact; defensive copies (immutable). |
| C-core3 | Convenience constructor | the no-`requiredTags` constructor still compiles every existing caller unchanged. |
| C-core4 | Whole-repo build | `./gradlew build` green — **all existing** core/security/starter/catalog/user-service tests pass unchanged (proves additivity). |
| RD1 | Persist on role-def | the user-service role-def create/update accepts + stores `requiredTags`/`matchMode` (JSONB + string column). |
| RD2 | Resolve API passthrough | `/internal/effective-role` returns the role with `requiredTags`/`matchMode` inside the `core.RoleDefinition`. |
| RD3 | Subset-rule compat | adding `requiredTags` only narrows a role → still passes the Phase-4 subset rule. |

## Rego tag match — ticket 5

| # | Case | Expected |
|---|------|----------|
| T1 | ANY_OF hit | one of the required keys' values intersects the resource's tags → `tags_satisfied` true → allow (with permission). |
| T2 | ANY_OF miss | no required key matches → `tags_satisfied` false → deny. |
| T3 | ALL_OF hit | every required key matches → allow. |
| T4 | ALL_OF partial | one required key matches, another doesn't → **deny** (universal `every`). |
| T5 | Multi-value intersection | resource `region=[emea,amer]` vs required `region:[emea]` → satisfied (array ∩ acceptable). |
| T6 | No required tags (vacuous) | a role with no `required_tags` → `tags_satisfied` true → behaves exactly as Phase 4 (back-compat). |
| T7 | Permission ok, tags fail | verb present in `permissions[type]` but tags unsatisfied → **deny** (both checks required). |
| T8 | Tags ok, permission fail | tags satisfied but verb absent → **deny**. |
| T9 | Malformed `required_tags` | a typed-wrong/missing-when-expected `required_tags` → `tags_satisfied` fails → **deny** (fail-closed). |
| T10 | Default deny | `default allow := false` holds; no rule path leaks an allow. |

## E2E — tag-gated, through the gateway — ticket 6

| # | Case | Expected |
|---|------|----------|
| X1 | Tag-match read | a member with the tag-gated role reads the **matching-tag** Category → **200**. |
| X2 | **Tag-mismatch read (the decisive case)** | the **same** member reads the **non-matching-tag** Category → **403** — identical role permission, only the tags differ. |
| X3 | ANY_OF vs ALL_OF | a role requiring two keys: both-satisfied → 200; only-one-satisfied → 403 (ALL_OF) / 200 (ANY_OF). |
| X4 | Dictionary define (dogfood) | owner defines a team-scoped key → 200; a member attempts define → **403**. |
| X5 | Illegal assignment | assigning a value outside the dictionary → **422**. |
| X6 | Runtime dynamism | define a new team key → assign it → a role requires it → a decision flips, **no redeploy**. |
| X7 | Tokens minted in-network; stable across reruns | green twice; chained ids in collection scope. |

## Cross-cutting

| # | Case | Expected |
|---|------|----------|
| CC1 | `./gradlew build` | all library modules + **both** example apps + codegen + ITs green. |
| CC2 | Library public API | the **only** change is the additive `RoleDefinition.requiredTags`/`matchMode`; security/data/starter APIs unchanged; every old test passes. |
| CC3 | Clean-room scan of the diff | no proprietary names/paths/ticket-ids (no `TagDictionary`/`@AutoTag`/source package names). |
| CC4 | Fail-closed | illegal tag → 422 (never stored); definitions-fetch failure → write rejected; malformed `required_tags` → deny; OPA default-deny. |
| CC5 | Three layers separate | define = governance (`team:define-tags`); assign = a normal `write`; requirement = role + Rego; no new assignment capability; match in Rego not Java. |
| CC6 | Dynamic dictionary | a team-scoped key created at runtime governs assignment + decisions immediately; global/system keys immutable. |
| CC7 | `ddl-auto: validate` | clean boot (Liquibase owns `tag_definition` + the role-def `required_tags` column). |
