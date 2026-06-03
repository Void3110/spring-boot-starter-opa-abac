---
tags:
  - status/planned
  - type/architecture
  - area/user-service
  - area/abac
---

# Dynamic tag dictionary — design

> Part of [[POC-ROADMAP]] Phase 4.5. The index is [[TAG-DICTIONARY]]; the work breakdown is
> [[01-DECOMPOSITION]]; the cases are [[10-QA-TEST-CASES]]. This note is the *why* and *shape*. Study
> background: [[RESEARCH-AUTOTAG-AND-FILTERING]].

## Problem

Phase 4 ([[USER-MANAGEMENT-SERVICE]]) made authorization **role-definition-driven** and **app-resolved**:
the user-service resolves a caller's effective `RoleDefinition` for a resource, and the catalog passes it
to OPA, which decides on `permissions{resourceType:[verbs]}`. That proves *role*-based access — but the
decision has no **controlled resource attributes** to reason about, and a role has no way to say *"you may
touch resources that look like **this**"*. Tags exist on resources ([[DOMAIN-MODEL]] `ResourceTags` JSONB)
but there is **no vocabulary** governing them and **no grant** that consumes them.

This slice supplies the missing **attribute** half, as a small demonstrable feature in three layers:
a **dynamic tag dictionary**, **tag assignment** to sub-resources, and **tag-based grants** matched in
Rego. The headline is the dictionary being a **runtime entity** (global *and* team-scoped), where the
source platform hardcodes tag keys — "the source hardcodes tags; we do it properly."

**Scope (decided):** the dictionary + management + assignment + role `requiredTags` + the Rego match + an
e2e matrix. **Out of scope:** `@AutoTag` auto-population (deferred), partial-eval list filtering (Phase 5),
ReBAC-in-Rego (Phase 7).

## The three separable layers (the core idea)

The source platform tangles **definition**, **population**, and **validation** in one `tag/` package. The
clean design keeps three concerns apart — each a distinct teaching point and a distinct ticket cluster:

| Layer | What it is | Where |
|-------|-----------|-------|
| **Definition** | A `TagDefinition` row: the legal *key*, its *scope*, *value type*, *cardinality*, optional *allowed values*. The dictionary. | user-service (`…usermgmt`) |
| **Assignment** | Attaching dictionary-validated values to a resource on create/update; stored in `ResourceTags` JSONB. | catalog (`…catalog`) |
| **Requirement** | A role definition's `requiredTags` + `matchMode`; the policy grants when the resource's tags satisfy it. | core `RoleDefinition` (additive) + per-type `.rego` |

> Definition is **governance** (who curates the vocabulary), assignment is a **write** (attach legal
> values), requirement is **authorization** (tags grant access). Conflating them is exactly the trap the
> source platform fell into; separating them is the design.

## Layer 1 — the `TagDefinition` entity (the dictionary)

A first-class entity in the user-service, beside `Team` / `RoleDefinition` / `TeamMembership`:

```
TagDefinition(
    id,
    key,                 // e.g. "sensitivity", "region", "cost-center"  (kebab-case)
    scope,               // GLOBAL (system) | TEAM
    teamId?,             // null for GLOBAL; the owning team for TEAM
    valueType,           // STRING | ENUM
    cardinality,         // SINGLE | MULTI
    allowedValues jsonb, // non-empty for ENUM; null/empty for STRING
    valuePattern?,       // optional regex for STRING valueType
    system               // true for seeded global keys (immutable via API)
)
```

- **Scope = GLOBAL or TEAM — reusing the shipped role-definition pattern.** This is the *exact* system-vs-
  team split we already built for `RoleDefinition` (mx-40324e): a **partial unique index** so a key is
  unique among globals and unique within a team, independently:
  ```sql
  CREATE UNIQUE INDEX uq_tag_def_global_key ON tag_definition (key)         WHERE team_id IS NULL;
  CREATE UNIQUE INDEX uq_tag_def_team_key   ON tag_definition (team_id, key) WHERE team_id IS NOT NULL;
  ```
  A team may define `region` even if a global `region` exists (team scope shadows/extends — document the
  resolution: team key wins for that team's resources). GLOBAL keys are **seeded** and `system = true`.
- **`valueType`** — `STRING` (free-form, optional `valuePattern` regex) or `ENUM` (a closed
  `allowedValues` set, capped at a small N, e.g. 50, mirroring AWS tag policies / Databricks governed
  tags). Generalizes the source's `EnumTagRule` / `RegexTagRule` into **data on the row** rather than code.
- **`cardinality`** — `SINGLE` (one scalar value) or `MULTI` (a set of values). The storage is free:
  `ResourceTags` already serializes a **scalar string** *and* a **string array** in the same JSONB column
  ([[DOMAIN-MODEL]]), so `SINGLE` → string tag, `MULTI` → array tag, no schema change on the catalog side.
- **`system`** — seeded global keys (e.g. a demo `sensitivity` enum, a `region` enum) are immutable through
  the API (update/delete → 409), exactly like seeded system role definitions.

### Seeded global demo keys (illustrative)
- `sensitivity` — `GLOBAL`, `ENUM`, `SINGLE`, `allowedValues = [public, internal, confidential]`.
- `region` — `GLOBAL`, `ENUM`, `MULTI`, `allowedValues = [emea, amer, apac]`.

These make the e2e matrix concrete without any team setup; team-scoped keys (e.g. a custom `tier`) prove
the runtime-editable half.

## Layer 2 — assignment (attach validated tags to a sub-resource)

The **Category** (the sub-resource under a Catalog) is where assignment is demonstrated — it already
carries `ResourceTags` as an `AbstractSecuredEntity`. On create/update the caller supplies a
`tags` map; the catalog **validates each entry against the applicable `TagDefinition`** before persisting:

1. the key must be a known definition (a **GLOBAL** key, or a **TEAM** key for the team that governs this
   resource's catalog — resolved via the same team-target indirection the resolve API uses);
2. the value(s) must satisfy `valueType` (enum membership / regex) and `cardinality` (scalar vs set);
3. unknown keys / illegal values → **422** with a clear message (fail-closed: an invalid tag is never
   silently dropped or stored).

Validation needs the dictionary. Two options (decided below): the catalog calls the user-service for the
applicable definitions (consistent with the app-resolved philosophy), **or** a small read endpoint returns
the definitions for a resource's governing team. **Decision:** an **internal read endpoint** on the
user-service (`GET /internal/tag-definitions?resourceType&resourceId` → the GLOBAL keys + the governing
team's keys), cached; the catalog validates locally against that set. Mirrors the resolve-API shape and
keeps the user-service the single authority for the vocabulary.

- **Authorization to assign = a normal write.** Any member whose role grants `write` on the Category may
  attach tags; the dictionary constrains *what* is legal, the existing `@OpaPreAuthorize(category:write)`
  governs *who*. No new capability for assignment (decided).

## Layer 3 — the requirement + the Rego match (the grant)

This is what makes tags *authorization* and not just metadata.

### `RoleDefinition` gains two optional fields (the one library change)

`opa-abac-core`'s `RoleDefinition` is extended **additively** and **backward-compatibly**:

```java
public record RoleDefinition(
        String code,
        Map<String, Object> attributes,
        Map<String, List<String>> permissions,
        // NEW — both optional; absent ⇒ "no tag requirement" (old behavior unchanged):
        Map<String, List<String>> requiredTags,   // {tagKey -> [acceptable values]}
        TagMatchMode matchMode                     // ANY_OF | ALL_OF (default ANY_OF)
) { … }
```

- **Backward compatible:** a role with no `requiredTags` serializes the same as today (the fields are
  `@JsonInclude(NON_NULL/NON_EMPTY)`), so every existing policy, test, and the Phase-4 resolve path keep
  working untouched. This is the **only** deliberate public-API change in the slice, and it only *adds*.
- `requiredTags` is `{tagKey -> [acceptable values]}` — e.g. `{sensitivity: [public, internal]}` means
  "the resource's `sensitivity` tag must be one of these". `matchMode` decides how multiple required keys
  combine.

### The OPA `input` shape (additive)

The catalog already sends `role_definition` inside `input`. It now carries the two new fields, and
`resource` already carries the resource's tags. Illustrative:

```json
{
  "input": {
    "subject": { "id": "…", "roles": ["…"], "attributes": {} },
    "action": "category:read",
    "resource": {
      "type": "category",
      "id": "…",
      "attributes": { "tags": { "sensitivity": "internal", "region": ["emea","amer"] } }
    },
    "role_definition": {
      "code": "regional-reader",
      "permissions": { "category": ["read"] },
      "required_tags": { "sensitivity": ["public","internal"], "region": ["emea"] },
      "match_mode": "ALL_OF"
    }
  }
}
```

### The match in Rego (`some in` / `every`)

The per-type `.rego` (e.g. `category.rego`) gains a `tags_satisfied` rule consulted **in addition to** the
existing permission check (`allow` requires *both* the verb in `permissions[type]` **and** `tags_satisfied`).
When a role has no `required_tags`, `tags_satisfied` is vacuously true (so non-tagged roles are unaffected):

```rego
# a single required key is satisfied if the resource's tag value(s) intersect the acceptable set
key_satisfied(key, acceptable) if {
    some v in resource_tag_values(key)   # resource's value(s) for `key` (scalar → singleton set)
    v in acceptable
}

# ANY_OF: at least one required key is satisfied  (existential — `some`)
tags_satisfied if {
    input.role_definition.match_mode == "ANY_OF"
    some key, acceptable in input.role_definition.required_tags
    key_satisfied(key, acceptable)
}

# ALL_OF: every required key is satisfied  (universal — `every`)
tags_satisfied if {
    input.role_definition.match_mode == "ALL_OF"
    every key, acceptable in input.role_definition.required_tags {
        key_satisfied(key, acceptable)
    }
}

# vacuous truth: a role with no required tags is unaffected (back-compat)
tags_satisfied if {
    not input.role_definition.required_tags
}
```

- **ANY_OF** uses `some … in` (existential) — "at least one"; **ALL_OF** uses `every` (universal) — "all".
  This is the industry-standard semantics: AWS `ForAnyValue:`/`ForAllValues:`, Cerbos `any`/`all`, OpenFGA
  conditions. Doing it in Rego is the whole point of the library and the bridge to Phase-7 ReBAC.
- **Default-deny preserved:** `allow` still defaults false; the tag rule only *narrows* access (a role must
  pass both the permission and the tag check). A missing/typed-wrong `required_tags` → `tags_satisfied`
  fails → deny (fail-closed).

## Who manages what (decided)

| Operation | Capability required | Mechanism |
|-----------|---------------------|-----------|
| Define / edit a **team-scoped** tag key | `owner` or `administrator` (management capability) | new `team:define-tags` action on the user-service `team.rego`; dogfooded `@OpaPreAuthorize` |
| Edit a **GLOBAL/system** tag key | nobody via API (seeded, immutable) | update/delete a `system=true` definition → 409 |
| **Assign** validated tag values to a resource | `write` on that resource | the existing `@OpaPreAuthorize(<type>:write)`; the dictionary only constrains legality |
| Set a role's **`requiredTags`** | the same capability that manages role definitions (`owner`, per Phase-4 `roledef:write`) | extends the Phase-4 role-def management endpoint |

This mirrors Databricks governed-tags / AWS tag-policy governance: **admins curate the dictionary; writers
assign from it; the role author decides what tags a role requires.**

## Considered & rejected

| Option | Why rejected (for now) |
|--------|------------------------|
| **Match the required-tags in Java** (the app-resolved style of Phase 4) | The maintainer chose **Rego** deliberately: it is the OPA-native expression of ABAC, showcases the library's reason to exist, and is the on-ramp to Phase-7 ReBAC. App-side matching would hide the interesting logic from the policy. (A Java fallback was explicitly *not* requested — single engine, less surface.) |
| **Hardcoded tag-key constants + a code-registered validation registry** (the source platform's model) | That is precisely what this slice improves on. The dictionary is a **runtime entity**; `valueType`/`allowedValues` live on the row, editable without a redeploy. |
| **Team-scoped only** (no global keys) | Loses the system-vs-custom contrast that makes the demo legible and the seeded demo keys that let the e2e matrix run without team setup. Both scopes, reusing the role-def partial-unique pattern. |
| **Global only** (defer team scope) | The team angle is the *interesting* part the maintainer raised; the partial-unique pattern is already proven, so team scope is cheap to include now. |
| **A new `team:assign-tags` capability** | Over-governs. Assigning *legal* values is just a write; the dictionary already constrains legality. A distinct capability would duplicate the write authorization. (Revisit only if a tag needs stricter-than-write assignment control.) |
| **Port `@AutoTag` + the JPA processor** | Auto-*population* is orthogonal machinery (a reflection-driven `@PrePersist` listener) and large; it doesn't help prove the dictionary or the grant. Tags are assigned explicitly (as the catalog already does). Documented + deferred. |
| **A new top-level `tag_assignment` table** | Unneeded — assigned tags live in the resource's existing `ResourceTags` JSONB ([[DOMAIN-MODEL]]); only the *definitions* are a new table. Keeps the storage model and the GIN-index investment intact. |
| **Match resource tags against the *subject's* tags** (AWS `PrincipalTag == ResourceTag` style) | A different (and also valid) ABAC shape, but it bypasses the role-definition backbone this PoC is built around. Here the **role** carries the requirement; subject-vs-resource tag equality is a documented future variant, not this slice. |
| **Multi-value via a separate join table** | The `ResourceTags` array tag (a JSONB string array) already models multi-value; a join table would fight the established JSONB model and the Phase-5 partial-eval plan. |

## Module placement

- **user-service (`…usermgmt`):** new `TagDefinition` entity + repository + Liquibase + seed; a dictionary
  management API (define/list/edit team-scoped keys, list global keys); the new `team:define-tags` action
  in its `team.rego`; the internal `GET /internal/tag-definitions` read endpoint; extend the role-def
  management endpoint to accept `requiredTags`/`matchMode`. Dogfoods the starter as before.
- **catalog (`…catalog`):** validate assigned tags on Category create/update against the dictionary
  (fetched from the user-service); the per-type `category.rego` gains the `tags_satisfied` rule; the
  resolve path already delivers the role's `requiredTags` (they ride inside `RoleDefinition`).
- **library (`opa-abac-core`):** the **only** code change — add the two optional `RoleDefinition` fields
  + a `TagMatchMode` enum, additively and backward-compatibly. No change to `opa-abac-spring-security` /
  `-spring-data` / `-starter` public APIs.
- **infra:** seed demo tag definitions + a demo Category with tags; extend the e2e matrix; OPA policy
  reload for the updated `category.rego`.

## Deferred to later phases / variants

`@AutoTag` auto-population (a JPA listener) · subject-tag-vs-resource-tag equality matching · partial-eval
→ JPA `Specification` list filtering over the same tags JSONB (Phase 5) · ReBAC-in-Rego (Phase 7) ·
hierarchical tag inheritance (a Category inheriting its Catalog's tags) · per-tag assignment ACLs beyond
"write".

## Related
- Work breakdown: [[01-DECOMPOSITION]] · Run it: [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] · QA: [[10-QA-TEST-CASES]]
- Index: [[TAG-DICTIONARY]] · Prior slice: [[USER-MANAGEMENT-SERVICE]] · Spine: [[LIBRARY-SPINE]]
- Resource model: [[DOMAIN-MODEL]] (`ResourceTags` scalar+array JSONB) · Study notes: [[RESEARCH-AUTOTAG-AND-FILTERING]]
- Roadmap: [[POC-ROADMAP]] (Phase 4.5) · Follow-on: Phase 7 ReBAC-in-Rego (the "match in the policy" idea)
