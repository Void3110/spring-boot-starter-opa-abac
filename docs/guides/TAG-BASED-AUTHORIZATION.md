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

**Read:** `GET /api/v1/tag-definitions[?teamId=]` **and** `GET /api/v1/teams/{teamId}/tag-definitions`
(any authenticated caller — the vocabulary isn't sensitive, and an *assigner* needs it to know what is
legal to assign; the two paths serve identical rows, and only the team-scoped one is gateway-exposed).
**Manage** (a team's keys): `POST /api/v1/teams/{teamId}/tag-definitions` and
`PUT/DELETE /api/v1/teams/{teamId}/tag-definitions/{key}`, secured
by `@OpaPreAuthorize(team:define-tags)` — **owner or administrator** (admins curate the vocabulary writers
assign from). Global/system keys are immutable (409).

### Operator-managed keys (`operatorManaged`)

A definition also carries **`operatorManaged`** (boolean, default `false`) — a second, additive flag that
answers a **different question** from `system`:

| Flag | Protects | Seeded `sensitivity` / `region` | Seeded `env` |
|---|---|---|---|
| `system` | the **definition** — its shape is immutable through the API | `true` | `true` |
| `operatorManaged` | the **values** — no public API path may write them on any resource | `false` | `true` |

Both existing global keys are `system=true` yet freely assignable, which is exactly why the new meaning
needed its own flag rather than a reinterpretation of the old one. The flag is **never client-authorable**:
it appears in **no** request schema (and GLOBAL definitions have no public create path at all — only
`createTeamTagDefinition` exists), so a key becomes operator-managed by seeding, never by a client asking.

Its first user is **`env`** — the production tier ([[0030-step-up-decision-contract|ADR 0030]] §3):
GLOBAL, `ENUM`/`SINGLE` over `production | staging | dev`, seeded `system=true` **and**
`operatorManaged=true`. A supervisor's read of a catalog's *contents* is gated on the governing catalog's
`env`, so the tag must not be strippable by the people being supervised.

The flag travels to the catalog service through the internal projection
(`GET /internal/tag-definitions`) — enforcement lives where tag **values** are written, and the catalog
service cannot enforce what it never receives.

**Enforcement, and the one path that can write.** The catalog's `TagAssignmentService` rejects any
*public* write that **moves** an operator-managed key — an **assign** (absent → present), a
**re-value**, or a **strip** (present → absent) — with **`409 TAG_OPERATOR_MANAGED`**. The check is
**delta-based**, not presence-based, because these writes are full-map replace: an **echo** (the same
value on both sides) passes, since rejecting it would freeze every ordinary tag edit on a resource that
happens to carry the key. Two consequences worth knowing:

- Submitting `{}`/`null` over a resource that carries the key **is a strip** and is rejected — the
  empty-submission shortcut may only skip the dictionary fetch when there is nothing to protect either.
  So a tags-clearing write on a **tagged** resource now consults the dictionary, and a dictionary outage
  makes it **503** rather than silently succeeding: whether the cleared key was operator-managed is a
  question only the dictionary answers, and an unanswerable question fails closed.
- The 409 is deliberately *not* `TAG_DEFINITION_IMMUTABLE`: that code names protection of a
  definition's **shape**, and a caller must be able to tell which guard answered.

The **operator path** is the catalog service's in-network
**`POST /internal/bootstrap/resource-tags`** — a **merge-upsert** (`{resourceType, resourceId, tags}`;
only the posted keys change, a posted `null` removes one, everything else survives, and posting the same
map twice converges). It bypasses the rejection **by construction** — a separate service entry point
that never runs the delta check, rather than a flag a public caller could pass — while still validating
values against the dictionary. Like every `/internal/**` surface it is **gateway-unreachable**: APISIX
carries a positive `internal-blocked` route that 404s `/internal/*` at the edge.

> **The trust dependency, stated so it is not silently broken** (ADR 0030 §3): an **untagged** root counts
> as **non-production** and therefore opens. That default is defensible *only* while `env` has no public
> write path — otherwise a supervised owner could dodge the tier by leaving a catalog untagged. If `env`
> ever becomes publicly writable, the default must flip to deny-until-tagged.

## Layer 2 — assignment (validated against the dictionary)

On Catalog / Category / Product create/update the caller supplies a `tags` map (products became
taggable in ADR [[0025-taggable-products|0025]]; catalog and product writes use the same delta
dispatch as the category one — content delta → `update`, tags delta → `assign-tags`). Before persisting, the catalog fetches the
**applicable** definitions (global keys + the governing team's keys) from the user-service's internal
`GET /internal/tag-definitions?resourceType&resourceId`, via a fail-closed `TagDefinitionClient`, and
validates each entry (known key, value type, cardinality, regex). Valid tags land in the Category's `tags`
JSONB.

- **Authorization to assign = the `assign-tags` decision** (Phase 6.5, [[PERMISSION-MODEL]]): the
  category update handler dispatches on the request's deltas — a tags delta asks
  `category:assign-tags` (the `TAG` category), a content delta asks `category:update` (`WRITE`) —
  so tag curation and content editing are separately grantable, both directions. The
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
| grants the `READ` category (→ `view`/`list`), **no** `requiredTags` | **every** readable resource | no requirement → vacuously satisfied → the role's full reach |
| grants `READ` + `requiredTags={region:[emea]}` | **only** `region=emea` resources | the requirement narrows the reach to matching tags |
| grants `READ` + `requiredTags={region:[emea], tier:[gold]}`, `ALL_OF` | resources matching `emea` **and** `gold` | universal (`every`) over the required keys |
| grants `READ` + same two keys, `ANY_OF` | resources matching `emea` **or** `gold` | existential (`some … in`) over the required keys |
| has **no** role definition at all | **nothing** (`DENY_ALL` on lists) | the fail-closed boundary — `filter` requires `has_role_definition` |
| grants no `READ` category | **nothing** | the effective-actions check fails before tags are even considered |

> So the genuine empty list comes from **no role definition** (the fail-closed boundary), *not* from "a
> role with no tags". A role with the permission and no tag requirement sees everything it's permitted to —
> tags only ever *subtract*.

**The one carve-out — the governing root (ADR [[0022-root-read-tag-exemption|0022]]).** Uniform
subtraction had a lockout: a tag-requiring role stopped reaching the **untagged catalog shell** — team
member, correct role, empty grid — because membership only *selects* the role; it granted nothing the
role's filter couldn't void. Since ADR 0022, READ-level verbs (`view`/`list`) on the **catalog** (the
resource the team-target explicitly names) are exempt from `requiredTags` by default
(`data.config.root_read_tag_exemption`, shipped `true`; absent = strict, fail-closed;
`ROOT_READ_TAG_EXEMPTION=0 ./deploy.sh up` flips the live rig). Mutations on the root and everything
below it stay fully tag-gated — and catalogs are now **taggable** (the category-style delta dispatch on
the catalog PUT), so root tags gate *who may mutate the catalog itself*. The 7.0.5 list↔GET agreement
invariant holds in both flag states.

**A worked data-flow** (the e2e demo, traced through the rego):

```
Member holds role "regional-reader":  permissions={category:[READ]}, requiredTags={region:[emea]}, ANY_OF

GET /categories/X   where X is tagged region=apac
  1. resolve the member's effective role            → regional-reader
  2. load Category X, read its tags                 → {region: apac}
  3. POST to OPA: { role_definition:{permissions, required_tags, match_mode},
                    resource:{ attributes:{region: apac} } }
  4. rego:  granted = (view ∈ effective_actions(role, category))   ✅
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
    Map<String,List<String>> deniedActions,  // denied_actions — deny-overrides (Phase 6.5)
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

### Getting the tags to OPA — resolved at the gate (Phase 5.97)

A tag decision needs the resource's **tags**, which are only known once the instance is loaded. Since
Phase 5.97 ([[ATTRIBUTE-RICH-PRE-AUTHORIZATION]]), the pre-invocation gate does that itself: with an
`AbacResourceResolver` registered, a declared `resourceId` is **resolved** and the decision is made on
the instance's real tags (and ancestors), the role looked up once on the governing root — so tag
grants and tag-keyed denies are **decided declaratively at `@OpaPreAuthorize`**, no handler code. The
earlier post-load layer-3 check this guide used to describe (`CategoryAuthorizer`) existed only
because the gate was attribute-blind and was deleted with the flip; the library's
`HierarchicalAuthorizer` remains the programmatic alternative for non-annotation flows.

## Who manages what

| Operation | Capability | Mechanism |
|-----------|-----------|-----------|
| Define/edit a **team-scoped** tag key | `owner` or `administrator` | `@OpaPreAuthorize(team:define-tags)` on the user-service — the management verb; enforcement closed in Phase 6.7 — `define-tags` is granted by the `TAG` capability token (owner/administrator carry it; ADR [[0015-control-plane-vocabulary-categorization|0015]]) |
| Edit a **GLOBAL/system** key | nobody (seeded, immutable) | update/delete a `system` key → 409 |
| **Assign** validated values to a resource | the `TAG` category (`assign-tags`) on that resource | `@OpaPreAuthorize(<type>:assign-tags)` via the delta dispatch — tags delta asks `assign-tags`, content delta asks `update` |
| Set a role's **`requiredTags`** | the role-def management capability (`owner`) | extends the Phase-4 role-def API |

Mirrors Databricks governed-tags / AWS tag-policy governance: admins curate the dictionary; writers assign
from it; the role author decides what a role requires.

## The decisive demo (e2e)

The whole feature in one contrast — **two Categories, one role, different tags → one allowed, one denied**:

- A team-scoped `regional-reader` role grants the `READ` category on `category` and requires `region` ANY_OF `[emea]`.
- A member with that role reads a Category tagged `region=[emea]` → **200**.
- The **same** member reads a Category tagged `region=[apac]` → **403** — identical permission; only the
  tags differ.

Proven through the gateway by the tag matrix (see [[E2E-TESTING]] → "Tag-based ABAC matrix"). ANY_OF vs
ALL_OF, the dictionary define dogfood (owner 201 / member 403), and an illegal assignment (422) round it
out. A team key defined at runtime governs assignment + (via a role) decisions immediately — **no
redeploy**.

## Bringing your own tag dictionary (the library/app boundary)

Everything in Layers 1–2 is **example-app code, on purpose**. The starter ships the tag *mechanics*
— all of them key-agnostic — and owns **no dictionary**:

| The starter owns (opaque `key → value(s)`) | The application owns (vocabulary + workflow) |
|---|---|
| `ResourceTags` + `Taggable` (`TAGS_ATTRIBUTE`) — the JSONB storage shape (`opa-abac-spring-data`) | The `TagDefinition` model, GLOBAL/TEAM scoping, immutable system keys (user-service) |
| The partial-eval residual compiling `tags.*` conditions into SQL | Assignment validation (`TagAssignmentService` + the fail-closed `TagDefinitionClient`) |
| `RoleDefinition.requiredTags`/`matchMode` → `required_tags`/`match_mode` in OPA input (`opa-abac-core`) | The `/internal/tag-definitions` resolve endpoint + the `team:define-tags` authoring gate |
| `input.resource.tags` at the gate + enrichment | Which keys exist at all — seeded, user-managed, or none |

So an adopter may hardcode three keys, seed globals the way the example does (Liquibase,
`system=true`), run a full user-managed dictionary (the TEAM scope), or skip tags entirely — the
library behaves identically in all four. The only coupling that matters is between the *app's own*
policies/role definitions and the keys it actually assigns; dictionary validation is what keeps that
coupling honest, and the example is the reference implementation to copy from.

**The one integration trap (we hit it ourselves):** address the dictionary by the resource's
**governing root**, never the raw resource. The user-service resolves the applicable team by exact
team-target match, and teams target roots (catalogs) — so
`validateAndBuild("catalog", catalogId, …)`, not `("category", categoryId, …)`. Passing a non-root
still validates the globals but **silently drops the team's custom keys** (they stop resolving —
every team-key assignment answers 422 "unknown key"). Same caller-resolves-the-root rule the
effective-role fetch follows.

**Pinned decision (2026-07-09): no tag-dictionary interfaces in the starter for 1.0.** Every starter
SPI exists because library machinery calls it at decision time (`RoleDefinitionSupplier`,
`AncestorChainSupplier`, `GovernedScopeResolver`, `UserDirectory`…); nothing in the library ever
needs a tag *definition* — assignment validation is a write-path application concern. A dictionary
port would be the first seam with no library consumer, and it would freeze one vocabulary schema
(ENUM/STRING × SINGLE/MULTI) at 1.0 that isn't the starter's to own. If real adopter demand
appears, the move is additive and its shape is known: an **optional module** (the
`opa-abac-keycloak-directory` precedent) carrying a `TagDefinitionView` contract, the generic
assignment validator, and an ancestor-wired `applicableTo(resource)` source — which would also make
the governing-root trap disappear by construction.

## Boundaries (deferred)

`@AutoTag` auto-population (a JPA listener) · subject-tag-vs-resource-tag equality matching ·
ReBAC-in-Rego (Phase 8). *(Formerly listed here and since shipped: partial-eval → JPA list filtering
over the same `tags` JSONB — `filter_tags_satisfied`, [[PARTIAL-EVALUATION-FILTERING]] — and the
per-instance / hierarchical path, [[HIERARCHICAL-AUTHORIZATION]].)*

## Related
- [[TEAM-BASED-AUTHORIZATION]] (Phase 4, the app-resolved role) · [[ABAC-AUTHORIZATION]] (the spine)
- [[E2E-TESTING]] (the tag matrix) · the roadmap [[POC-ROADMAP]] (Phase 4.5)
- The shipped work package: `docs/to-do/implemented/TAG-DICTIONARY/` (design + decomposition + the
  autonomous prompt + per-ticket STATUS notes).
