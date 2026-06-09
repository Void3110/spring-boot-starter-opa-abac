---
tags:
  - status/done
  - type/guide
  - area/abac
  - area/user-service
---

# Tag-based authorization (the dynamic tag dictionary)

> Phase 4.5. How attribute-based grants work in this project: a **runtime tag dictionary**, **tag
> assignment** to resources, and **tag-based grants** matched in Rego. Builds on
> [[TEAM-BASED-AUTHORIZATION]] (Phase 4, the app-resolved role) and the [[ABAC-AUTHORIZATION]] spine.

## Why

Phase 4 made authorization **role-definition-driven**: a role's `permissions{type:[verbs]}` grants action
verbs per resource type. That is *role*-based. But a decision had no **controlled resource attributes** to
reason about, and a role had no way to say *"you may touch resources that look like **this**."* This slice
adds the **attribute** half — and does it *properly*: where the source platform hardcodes tag keys at
compile time, here a tag key is a **runtime-editable row**, global *and* team-scoped.

## The three separable layers

The whole design is keeping three concerns apart (the source platform tangles them in one package):

| Layer | What it is | Where |
|-------|-----------|-------|
| **Definition** | A `TagDefinition` row: a legal *key*, its *scope*, *value type*, *cardinality*, optional *allowed values*. The dictionary. | user-service (`…usermgmt`) |
| **Assignment** | Attaching dictionary-validated values to a resource on create/update; stored in the resource's `tags` JSONB. | catalog (`…catalog`) |
| **Requirement** | A role's `requiredTags` + `matchMode`; the policy grants when the resource's tags satisfy it. | core `RoleDefinition` (additive) + `category.rego` |

> **Definition** is governance (who curates the vocabulary). **Assignment** is a write (attach legal
> values). **Requirement** is authorization (tags grant access). Keep them separate.

## Layer 1 — the dictionary (`TagDefinition`)

A first-class entity in the user-service, beside `Team` / `RoleDefinition`:

```
TagDefinition(key, scope GLOBAL|TEAM, teamId?, valueType STRING|ENUM,
              cardinality SINGLE|MULTI, allowedValues jsonb, valuePattern?, system)
```

- **Scope** = `GLOBAL` (system, seeded, immutable) or `TEAM` (owner/administrator-defined, scoped to one
  team) — the same system-vs-team split as role definitions, via **two partial unique indexes** keyed on
  whether `team_id` is null (a key is unique among globals and, independently, within a team).
- **`valueType`** = `STRING` (free-form, optional `valuePattern` regex) or `ENUM` (a closed `allowedValues`
  set, capped). **`cardinality`** = `SINGLE` (a scalar) or `MULTI` (a set). The resource-side `tags` JSONB
  already stores both a scalar string *and* a string array, so multi-value is free.
- Seeded global demo keys: `sensitivity` (ENUM/SINGLE/`[public,internal,confidential]`) and `region`
  (ENUM/MULTI/`[emea,amer,apac]`).

**Read:** `GET /api/v1/tag-definitions[?teamId=]` (any authenticated caller — the vocabulary isn't
sensitive). **Manage** (a team's keys): `POST/PUT/DELETE /api/v1/teams/{teamId}/tag-definitions`, secured
by `@OpaPreAuthorize(team:define-tags)` — **owner or administrator** (admins curate the vocabulary writers
assign from). Global/system keys are immutable (409).

## Layer 2 — assignment (validated against the dictionary)

On Category create/update the caller supplies a `tags` map. Before persisting, the catalog fetches the
**applicable** definitions (global keys + the governing team's keys) from the user-service's internal
`GET /internal/tag-definitions?resourceType&resourceId`, via a fail-closed `TagDefinitionClient`, and
validates each entry (known key, value type, cardinality, regex). Valid tags land in the Category's `tags`
JSONB.

- **Authorization to assign = a normal `write`** — the existing `@OpaPreAuthorize(category:write)`; the
  dictionary only constrains *what* is legal, not *who* may attach. No new capability.
- **Fail-closed:** an unknown key / enum miss / cardinality mismatch → **422** (naming the offending key;
  never silently stored). A definitions-fetch failure → **503** (the write is rejected — a validation-input
  fetch must reject, not return an empty "all-allowed" set that would widen legality).

## Layer 3 — the requirement + the Rego match (the grant)

### Where the requirement lives — and what it means (read this first)

This is the part most worth being precise about, because there are two legitimate ABAC shapes and **this
project deliberately uses one of them**:

> **The tag requirement lives on the *role*, not on the resource.** A `RoleDefinition` carries
> `requiredTags` + a `matchMode`; a **resource carries only plain tag *values*** (e.g. `region=emea`). The
> requirement is a property of *the role's reach*: *"this role grants its permissions only on resources
> whose tags match."* The resource makes no demand of its own.

This is the **subject-side / condition-on-the-policy** model — the same shape as **AWS IAM ABAC**, where
one policy is attached to roles and resources carry plain tag values, and `matchMode` ALL_OF / ANY_OF are
the analogue of AWS's `ForAllValues` / `ForAnyValue`. The alternative — **resource-side** (a resource
*demands* a clearance the subject must possess, à la **Keycloak Authorization Services**, where a
permission attaches policies *to the protected resource*) — is **not** what this project does, and is a
deliberate choice: the subject-side model is **fail-closed by construction** and composes natively with
the Phase-5 partial-eval **row filter** (the requirement becomes a residual `WHERE` over the `tags`
column). The resource-side model's natural default is *"an untagged resource is open to everyone"* — a
documented **fail-open** default (AWS warns about the identical `ForAllValues`-over-empty hazard) that
would conflict with this repo's load-bearing fail-closed invariant. This choice is pinned by
**[ADR 0009](../architecture/adr/0009-tag-requirement-subject-side.md)** (with the rejected resource-side
alternative and the fail-open analysis). A future "resource-declared clearance" slice could add the
resource-side model *with the default inverted to fail-closed* — it would get its own ADR.

**What `requiredTags` is — and is NOT:**

- It is **NOT** used to decide *which roles a member may be assigned* (that's team/ownership rules in
  [[TEAM-BASED-AUTHORIZATION]] — tags play no part). It is a **per-request filter on the resource**,
  evaluated *every time* the member touches a resource.
- Tags on a role **narrow** its reach; they do **not** grant from an empty baseline. The truth table:

| The role… | …sees | Why |
|-----------|-------|-----|
| has the `read` permission, **no** `requiredTags` | **every** readable resource | no requirement → vacuously satisfied → the role's full reach |
| has `read` + `requiredTags={region:[emea]}` | **only** `region=emea` resources | the requirement narrows the reach to matching tags |
| has `read` + `requiredTags={region:[emea], tier:[gold]}`, `ALL_OF` | resources matching `emea` **and** `gold` | universal (`every`) over the required keys |
| has `read` + same two keys, `ANY_OF` | resources matching `emea` **or** `gold` | existential (`some … in`) over the required keys |
| has **no** role definition at all | **nothing** (`DENY_ALL` on lists) | the fail-closed boundary — `filter` requires `has_role_definition` |
| has no `read` permission | **nothing** | the permission check fails before tags are even considered |

> So the genuine empty list comes from **no role definition** (the fail-closed boundary), *not* from "a
> role with no tags". A role with the permission and no tag requirement sees everything it's permitted to —
> tags only ever *subtract*.

**A worked data-flow** (the e2e demo, traced through the rego):

```
Member holds role "regional-reader":  permissions={category:[read]}, requiredTags={region:[emea]}, ANY_OF

GET /categories/X   where X is tagged region=apac
  1. resolve the member's effective role            → regional-reader
  2. load Category X, read its tags                 → {region: apac}
  3. POST to OPA: { role_definition:{permissions, required_tags, match_mode},
                    resource:{ attributes:{region: apac} } }
  4. rego:  granted = (read ∈ permissions[category])   ✅
                      AND tags_satisfied                ❌   (apac ∉ [emea])
  5. → deny (403)

Same member, GET /categories/Y where Y is tagged region=emea → tags_satisfied ✅ → 200.
```

Identical permission; only the resource's tags differ. That contrast *is* the feature.

### The role carries the requirement (the one library change)

`opa-abac-core`'s `RoleDefinition` gains two **optional, additive** fields:

```java
record RoleDefinition(String code, Map<String,Object> attributes,
    Map<String,List<String>> permissions,
    Map<String,List<String>> requiredTags,   // {tagKey -> [acceptable values]}
    TagMatchMode matchMode)                   // ANY_OF | ALL_OF (default ANY_OF)
```

`@JsonInclude(NON_EMPTY/NON_NULL)` + a 3-arg convenience constructor mean a role with no requirement
serializes **byte-for-byte as before** — every existing policy/test/caller is unaffected. The user-service
persists the two fields and the resolve API (`/internal/effective-role`) returns them inside the resolved
`core.RoleDefinition`.

### The match in Rego (`some in` / `every`)

`category.rego`'s `allow` requires **both** the permission check **and** `tags_satisfied`:

```rego
# resource value(s) for a key as a set: array -> elements; scalar -> singleton; absent -> empty
resource_tag_values(key) := { ... }

key_satisfied(key, acceptable) if {
    some v in resource_tag_values(key)   # existential intersection
    v in acceptable
}

tags_satisfied if {                      # ANY_OF — at least one required key
    input.role_definition.match_mode == "ANY_OF"
    some key, acceptable in input.role_definition.required_tags
    key_satisfied(key, acceptable)
}

tags_satisfied if {                      # ALL_OF — every required key
    input.role_definition.match_mode == "ALL_OF"
    every key, acceptable in input.role_definition.required_tags {
        key_satisfied(key, acceptable)
    }
}

tags_satisfied if { not has_required_tags }   # vacuous — back-compat for untagged roles
```

- **ANY_OF** ≡ `some … in` (existential; AWS `ForAnyValue:`); **ALL_OF** ≡ `every` (universal; AWS
  `ForAllValues:`). Doing the match **in the policy** is the OPA-native expression of ABAC and the on-ramp
  to Phase-7 ReBAC-in-Rego.
- **Vacuous truth:** a role with no `required_tags` is unaffected (untagged roles behave exactly as Phase
  4). A *malformed* requirement (unknown/missing `match_mode`) matches none of the rules → `tags_satisfied`
  fails → **deny** (fail-closed). `default allow := false` is preserved.

### Getting the tags to OPA — the per-instance load-then-check

A tag decision needs the resource's **tags**, which are only known after the entity is loaded. The
pre-invocation `@OpaPreAuthorize` is type-level (it sees method arguments, not the loaded row), so the
catalog's `getCategory` does an explicit **load-then-check** (`CategoryAuthorizer`, example-app code on the
library's `OpaClient` + `RoleDefinitionSupplier` beans): it loads the Category, resolves the role against
the **governing Catalog** (a small demo-scoped hierarchy step), and calls OPA with the loaded Category as
the resource so its tags reach `input.resource.attributes`. The general per-instance/hierarchy path is
Phase 5; this is the minimal demo of the grant.

## Who manages what

| Operation | Capability | Mechanism |
|-----------|-----------|-----------|
| Define/edit a **team-scoped** tag key | `owner` or `administrator` | `@OpaPreAuthorize(team:define-tags)` on the user-service |
| Edit a **GLOBAL/system** key | nobody (seeded, immutable) | update/delete a `system` key → 409 |
| **Assign** validated values to a resource | `write` on that resource | the existing `@OpaPreAuthorize(<type>:write)` |
| Set a role's **`requiredTags`** | the role-def management capability (`owner`) | extends the Phase-4 role-def API |

Mirrors Databricks governed-tags / AWS tag-policy governance: admins curate the dictionary; writers assign
from it; the role author decides what a role requires.

## The decisive demo (e2e)

The whole feature in one contrast — **two Categories, one role, different tags → one allowed, one denied**:

- A team-scoped `regional-reader` role grants `category:read` and requires `region` ANY_OF `[emea]`.
- A member with that role reads a Category tagged `region=[emea]` → **200**.
- The **same** member reads a Category tagged `region=[apac]` → **403** — identical permission; only the
  tags differ.

Proven through the gateway by the tag matrix (see [[E2E-TESTING]] → "Tag-based ABAC matrix"). ANY_OF vs
ALL_OF, the dictionary define dogfood (owner 201 / member 403), and an illegal assignment (422) round it
out. A team key defined at runtime governs assignment + (via a role) decisions immediately — **no
redeploy**.

## Boundaries (deferred)

`@AutoTag` auto-population (a JPA listener) · subject-tag-vs-resource-tag equality matching · partial-eval
→ JPA `Specification` list filtering over the same `tags` JSONB (Phase 5) · the general per-instance /
hierarchical authorization path in the library (Phase 5) · ReBAC-in-Rego (Phase 7).

## Related
- [[TEAM-BASED-AUTHORIZATION]] (Phase 4, the app-resolved role) · [[ABAC-AUTHORIZATION]] (the spine)
- [[E2E-TESTING]] (the tag matrix) · the roadmap [[POC-ROADMAP]] (Phase 4.5)
- The shipped work package: `docs/to-do/implemented/TAG-DICTIONARY/` (design + decomposition + the
  autonomous prompt + per-ticket STATUS notes).
