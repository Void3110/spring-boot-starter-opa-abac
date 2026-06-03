---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# Dynamic tag dictionary (example) — Phase 4.5

> **Direction (as decided with the maintainer):** a **runtime-editable** tag dictionary layered onto the
> user-management-service — the source platform's hardcoded tag keys, **done properly**. Tag keys are
> **both global (system) and team-scoped (owner/admin-defined)**; values can be **single or multi-valued**
> and **free-form or a restricted enumeration**; the resource owner attaches validated tags to
> sub-resources; role definitions carry **required tags** with an **ANY_OF / ALL_OF** match; and the
> match that actually grants access is **evaluated in Rego**. Part of the [[POC-ROADMAP]] (Phase 4.5).
> The [[RESEARCH-AUTOTAG-AND-FILTERING]] note is the study background; this folder is the work package.

> **This turn produces the planning package only** (design + decomposition + autonomous prompt + QA +
> STATUS stubs), a structural twin of the shipped [[USER-MANAGEMENT-SERVICE]] and [[LIBRARY-SPINE]]
> packages. The maintainer runs the autonomous prompt separately; the `STATUS-0N.md` notes are filled in
> at each checkpoint during that run (workflow-as-artifact).

## Purpose

Phase 4 made authorization **role-definition-driven** and **app-resolved**: the user-service resolves a
caller's effective `RoleDefinition` for a resource and the catalog passes it to OPA. But the *only*
attributes a decision can read today are the role's `permissions{type:[verbs]}` and whatever tags happen
to sit on the resource. There is **no controlled vocabulary** for those tags and **no way for a role to
say "you may access resources tagged like *this*."**

This slice adds that missing half — the **attribute** machinery — as a small, demonstrable feature:

1. a **dynamic tag dictionary** (an entity, not a constants class) that defines the legal tag *keys*,
   their *scope*, *value type*, *cardinality*, and (optionally) their *allowed values*;
2. **tag assignment** — an owner/member attaches dictionary-validated tags to a sub-resource (a Category
   under a Catalog) when creating/updating it;
3. **tag-based grants** — a role definition carries **required tags** + a match mode, and OPA grants
   access when the resource's tags satisfy the requirement.

That third layer is what turns tags from passive metadata into **attribute-based authorization** — and it
is the lead-in to the Phase-7 ReBAC work (both express "subject relates to resource via attributes/edges
in the policy").

## What it improves on (the source platform — study-only)

The prior platform (per [[RESEARCH-AUTOTAG-AND-FILTERING]]) has a `@AutoTag`/processor for *populating*
resource tags and a tag-dictionary that is a **compile-time constants class** with a runtime
validation-rule registry. Its tag **keys are fixed at build time**; there is **no user-managed,
team-scoped, runtime-defined tag** at all. The generalization here:

| Source platform (study-only) | This slice (clean-room, "done properly") |
|------------------------------|-------------------------------------------|
| Tag **keys** are compile-time constants. | Tag keys are **dictionary rows** — created/edited at runtime. |
| Only "system" tags (owner/members/status, auto-populated). | **Global** system keys **and** **team-scoped** keys an owner/admin defines. |
| Validation rules registered in code. | `valueType` (`STRING`/`ENUM`) + optional `allowedValues` live **in the row**. |
| Tags are resource metadata; the role grants by `permissions`. | A role can **require tags** (`ANY_OF`/`ALL_OF`); the match grants access **in Rego**. |

> We deliberately **do not** port `@AutoTag` here. Auto-population is orthogonal machinery (a JPA
> listener); this slice is about the *dictionary* + the *grant*. Tags are assigned explicitly, as the
> catalog already does. `@AutoTag` stays a documented, deferred idea (see *Deferred*).

## The three separable layers (the core idea — decided)

The source tangles definition, population, and validation in one package. We keep three concerns
**separate** — each is a clean teaching point:

1. **Definition** — a `TagDefinition` row: `{ key, scope, teamId?, valueType, cardinality, allowedValues? }`.
   - `scope` = **GLOBAL** (system, seeded, immutable) **or** **TEAM** (owner/admin-defined, scoped to one
     team) — the **exact** system-vs-team split we already shipped for `RoleDefinition`, reusing the
     `team_id IS NULL` **partial-unique-index** trick (mx-40324e).
   - `valueType` = `STRING` (free-form, optional regex) **or** `ENUM` (a restricted set of `allowedValues`,
     ≤ N) — mirrors AWS tag policies / Databricks governed tags.
   - `cardinality` = `SINGLE` (a scalar) **or** `MULTI` (a set) — our `ResourceTags` JSONB already stores
     **both** a scalar string and a string array, so multi-value is free at the storage layer.
2. **Assignment** — attaching values to a resource on create/update, **validated against the applicable
   definitions**, stored in the existing `ResourceTags` JSONB ([[DOMAIN-MODEL]]).
3. **Requirement** — a role definition carries `requiredTags` + a `matchMode` (`ANY_OF` / `ALL_OF`); the
   policy grants when the resource's tags satisfy the requirement (the **grant** side).

## How the match works (decided: in Rego)

The any-of / all-of match is evaluated **in OPA**, not in Java — it is the most "OPA-native" expression of
the feature and showcases exactly what the library exists for. The vocabulary is industry-standard:

- **ANY_OF** ≡ "at least one required tag matches" ≡ AWS `ForAnyValue:` ≡ Rego **`some … in`** (existential).
- **ALL_OF** ≡ "every required tag matches" ≡ AWS `ForAllValues:` ≡ Rego **`every`** (universal).
  (Cerbos calls these `any`/`all`; OpenFGA models them as conditions.)

The catalog's OPA `input` already carries `resource` (with its tags) and `role_definition`. This slice
adds the role's `required_tags` + `match_mode` into the serialized `role_definition`, and the per-type
`.rego` gains a `tags_satisfied` rule using `some in` / `every`. **No wire-contract break** — it is an
additive field on the existing `role_definition` object, defaulted/absent for roles that don't use it.

> This is also the natural bridge to **Phase 7 (ReBAC-in-Rego)**: both push the relationship/attribute
> match into the policy. Doing the tag match in Rego now makes that step smaller.

## Who manages what (decided: owner/admin define · members assign)

Reuses the Phase-4 management capability ladder (`owner` > `administrator` > `member`/`viewer`) and the
`@OpaPreAuthorize`-dogfooding pattern:

- **Defining** a team-scoped tag key (a dictionary row) requires a **management capability** — `owner` or
  `administrator` — via a new `team:define-tags` action on the user-service's `team.rego`. Global system
  keys are seeded and **immutable** through the API.
- **Assigning** validated tag values to a sub-resource is a **normal write** — any member whose role grants
  `write` on the resource can do it (no new capability). The dictionary constrains *what* is legal; the
  existing write authorization governs *who* may attach.

This matches Databricks governed-tags / AWS tag-policy governance (admins curate the dictionary; writers
assign from it).

## Where it lives

- **Dictionary + management API:** the **user-management-service** (`…usermgmt`) — it already owns the
  team/role authority, so the team-scoped dictionary belongs beside teams and role definitions. The
  dictionary rows and the `team:define-tags` authorization are dogfooded there.
- **Tag assignment + the required-tags match:** the **catalog app** (`…catalog`) is the *resource* side —
  Categories get tagged; the per-type `.rego` does the `tags_satisfied` match. The role's `requiredTags`
  ride along inside the `RoleDefinition` the catalog already receives from the user-service resolve API.
- **Library:** the `opa-abac-core` `RoleDefinition` gains two optional fields (`requiredTags`, `matchMode`)
  — the **one** deliberate, additive, backward-compatible library change this slice makes (an absent field
  means "no tag requirement"). Everything else is example-app + infra + rego.

## Boundaries

- **Unpublished** demo machinery, like the rest of `example/`. PoC, not a shippable artifact.
- **Not** a general policy/tag-governance product — it does only what the demo needs to make
  attribute-based decisions interesting and teachable.
- Same stack: Java 21 · Spring Boot 3.4 · Postgres + Liquibase · OpenAPI codegen · Testcontainers ITs ·
  OPA 1.10.x · the existing APISIX/Keycloak rig.

## This package (design → autonomous-implement → track)

The full work package for Phase 4.5, written to be **implemented autonomously** — same shape as the
shipped [[USER-MANAGEMENT-SERVICE]] and [[LIBRARY-SPINE]] slices.

| File | Role |
|------|------|
| `TAG-DICTIONARY.md` | This note — the index: purpose, the three layers, who-manages-what, where it lives. |
| [`00-DESIGN.md`](00-DESIGN.md) | The design: the `TagDefinition` entity, global+team scope, value-type/cardinality/allowed-values, the assignment + validation path, the `requiredTags`/`matchMode` role-def extension, the Rego `some in`/`every` match, the OPA-input shape, considered-&-rejected. |
| [`01-DECOMPOSITION.md`](01-DECOMPOSITION.md) | The ordered tickets — each Goal / Deliverables / Acceptance / What-NOT-to-touch. **The implementer's work list.** |
| [`AUTONOMOUS-IMPLEMENTATION-PROMPT.md`](AUTONOMOUS-IMPLEMENTATION-PROMPT.md) | Self-contained prompt to implement this package autonomously, ticket by ticket, with a review gate + checkpoints. |
| [`10-QA-TEST-CASES.md`](10-QA-TEST-CASES.md) | The unit / integration / policy / e2e cases the work must satisfy. |
| `STATUS-01.md` … `STATUS-06.md` | One per ticket — filled in at each checkpoint during the run. |

### Tickets (status)

| # | Ticket | Status | Note |
|---|--------|--------|------|
| 1 | Tag-definition domain (`TagDefinition`: scope/valueType/cardinality/allowedValues) + Liquibase + seed global system keys + read API | ✅ done | `STATUS-01.md` |
| 2 | Dictionary management API (owner/admin define team-scoped keys; `team:define-tags`; validation rules; system keys immutable) | ✅ done | `STATUS-02.md` |
| 3 | Tag assignment on the Category sub-resource (validated against the dictionary; stored in `ResourceTags`; member-with-write assigns) | ✅ done | `STATUS-03.md` |
| 4 | `RoleDefinition` extension: `requiredTags` + `matchMode` (ANY_OF/ALL_OF) — additive core field + role-def management | ✅ done | `STATUS-04.md` |
| 5 | Rego tag match (`some in` / `every`; OPA input carries resource tags + the role's required tags) | ⬜ planned | `STATUS-05.md` |
| 6 | e2e matrix (tag-gated allow/deny through the gateway) + docs + roadmap/Mulch | ⬜ planned | `STATUS-06.md` |

Critical path **T1 → T2 → T3 → T4 → T5 → T6**. T2 (define) and T3 (assign) both depend on T1's entity;
T4 (the role-side `requiredTags`) and T5 (the Rego match) are the **grant** half and depend on T3 having
real tags to match against. T6 is the rig/e2e + docs.

### Workflow-as-artifact
Like the prior slices, the verbatim [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] + the `STATUS-0N.md` notes are
**deliberate deliverables** — a studyable record of the plan→autonomous-implement→test→review workflow.
On ship the folder moves to `docs/to-do/implemented/` with a "Shipped" banner, alongside
[[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]], and [[USER-MANAGEMENT-SERVICE]].

## Related

- Overall roadmap: [[POC-ROADMAP]] (Phase 4.5).
- Prior slice this builds on: [[USER-MANAGEMENT-SERVICE]] (Phase 4) — owns the teams/role-defs authority
  and the dogfooded `@OpaPreAuthorize` this dictionary extends.
- The spine it rides on: [[LIBRARY-SPINE]] — ships `RoleDefinition` + the `RoleDefinitionSupplier` SPI and
  the per-type `.rego`; this slice adds `requiredTags`/`matchMode` to the role and a `tags_satisfied` rule
  to the policy.
- Study background: [[RESEARCH-AUTOTAG-AND-FILTERING]] — the source-platform tag machinery + the Phase-5
  list filtering that reuses the same `tags` JSONB.
- Resource model: [[DOMAIN-MODEL]] — `ResourceTags` (scalar + array JSONB) is where assigned tags land.
- Follow-on: Phase 7 ReBAC-in-Rego (the team-grant join in-policy) — the same "match in the policy" idea.
- IP boundary: root `CLAUDE.md` → "IP Boundary".
