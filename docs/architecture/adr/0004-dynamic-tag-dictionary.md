---
tags:
  - status/active
  - type/architecture
  - area/user-service
  - area/abac
---

# ADR 0004 — The dynamic tag dictionary: three layers, global + team, match-in-Rego

**Status:** Accepted (Phase 4.5) — *layer 2's "assignment is a normal `write`" and the rejected
`assign-tags` capability were superseded in Phase 6.5/6.7 ([[0007-coarse-grained-permission-categories|ADR 0007]] /
[[0015-control-plane-vocabulary-categorization|ADR 0015]]): a dedicated `assign-tags` fine action now
exists under the `TAG` category and gates assignment via the delta dispatch. Per the ADR convention the
body below is unedited.*
**Date:** 2026-06
**Context tags:** user-service, tag dictionary, ABAC, attribute-based grants

## Context

Phase 4 (ADR 0003) made authorization role-definition-driven — but a decision had no **controlled
resource attributes** to reason about, and a role had no way to say *"you may touch resources that look
like **this**."* Resources already carry tags (a JSONB `tags` column on every secured entity), but there
was **no vocabulary** governing those tags and **no grant** that consumes them. We want the attribute half
of ABAC, and we want to do it *properly*: where the reference platform fixes tag keys at compile time, a
tag key here should be a runtime-editable row, global *and* team-scoped.

## Decision

Three **separable** layers (the reference platform tangles definition, population, and validation in one
package; keeping them apart is the design):

1. **Definition — `tag_definition`** (a dictionary row): `{key, scope GLOBAL|TEAM, teamId?, valueType
   STRING|ENUM, cardinality SINGLE|MULTI, allowedValues, valuePattern?, system}`. Same global-vs-team
   shape as role definitions, reusing the **two partial unique indexes** (a key is unique among globals
   and, independently, within a team). Owner/administrator **define** team keys via
   `@OpaPreAuthorize(team:define-tags)`; global/system keys are seeded and immutable (409).
2. **Assignment** — attaching dictionary-validated values to a resource on create/update, stored in the
   existing `tags` JSONB (scalar → string tag, array → string-array tag; SINGLE/MULTI for free). The
   catalog fetches the *applicable* definitions from the user-service and validates; an illegal value →
   422, a definitions-fetch failure → 503 (**fail-closed as a rejection**, never an empty all-allowed
   set). Assigning is a **normal `write`** — no new capability; the dictionary only constrains legality.
3. **Requirement + match** — the role carries optional `requiredTags{key:[acceptable]}` + a `matchMode`,
   and the policy grants when the resource's tags satisfy it. **The match is evaluated in Rego**:
   `tags_satisfied` via `some … in` (ANY_OF, existential) / `every` (ALL_OF, universal); a role with no
   requirement is vacuously satisfied (back-compat); a malformed requirement fails closed → deny.

> Definition is **governance**, assignment is a **write**, requirement is **authorization**. The
> dictionary constrains *legality*; the existing write authorization governs *who assigns*; the role + Rego
> govern *the grant*.

**The one library change is additive.** `core.RoleDefinition` gains `requiredTags` + `matchMode`
(`@JsonInclude` so absent ⇒ the old wire shape; a 3-arg convenience constructor so every caller compiles
unchanged). Everything else is example-app + infra + rego. A whole-repo build with every pre-existing test
unchanged is the additivity proof.

**Governance model** (Databricks governed-tags / AWS tag-policy shaped): admins curate the dictionary
(`team:define-tags`, granted to owner **and** administrator — distinct from owner-only `define-roles`);
writers assign legal values; the role author decides what a role requires.

## Considered options

| Option | Why not |
|--------|---------|
| **Hardcoded tag-key constants + a code-registered validation registry** (the reference model) | Exactly what this improves on. The dictionary is a **runtime entity**; `valueType`/`allowedValues` live on the row, editable without a redeploy. |
| **Match the required tags in Java** (app-resolved style) | The maintainer chose **Rego** deliberately: it's the OPA-native expression of ABAC, showcases the library's reason to exist, and is the on-ramp to Phase-7 ReBAC-in-policy. App-side matching would hide the interesting logic from the policy. |
| **Team-scoped only** / **global only** | Loses the system-vs-custom contrast (seeded demo keys let the e2e run without team setup) *or* the runtime-editable team angle. Both scopes, reusing the proven partial-unique pattern, is cheap. |
| **A new `team:assign-tags` capability** | Over-governs. Assigning *legal* values is just a write; the dictionary already constrains legality. A distinct capability would duplicate the write authorization. |
| **A separate `tag_assignment` table** | Unneeded — assigned tags live in the resource's existing `tags` JSONB; only the *definitions* are a new table. Keeps the storage model + GIN index intact. |
| **A shared `TagValueValidator` in the library** (so both services validate identically) | Would force a non-additive library change (the only allowed library change is the `RoleDefinition` field). The two example apps validate against the **wire contract** (`TagDefinitionView`) instead — the user-service is the dictionary authority, the catalog is the fail-closed enforcement point. |
| **Port an auto-tagging JPA processor** (declarative auto-population of resource tags) | Auto-*population* is orthogonal machinery (a reflection-driven `@PrePersist`/`@PreUpdate` listener) and large; it doesn't help prove the dictionary or the grant. Tags are assigned explicitly; auto-population is a documented, deferred idea. |
| **Definitions-fetch failure → empty (all-allowed) set** | An empty applicable-set would *widen* legality (or make every tag illegal-by-absence). A validation-input fetch must fail closed to a **rejection** (503), the opposite shape from a role-supplier fetch (which fails closed to empty → deny). |

## Consequences

- **Good:** tags become real attribute-based authorization, matched in Rego; the dictionary is genuinely
  dynamic (a team key created at runtime governs assignment + decisions immediately, no redeploy); the
  three layers stay independently testable; the library change is provably additive; the decisive demo
  (two Categories, one role, different tags → 200 vs 403) holds end-to-end through the gateway.
- **Cost:** a per-instance, tag-aware decision needs the resource's tags at decision time, which the
  type-level `@OpaPreAuthorize` can't supply (it runs pre-invocation against method args). The demo uses
  an example-app **load-then-check** (`CategoryAuthorizer`) that loads the entity, resolves the role on
  the governing parent, and calls OPA with the loaded resource. The **general** per-instance /
  hierarchical path belongs in the library and is deferred to **Phase 5**.
- **Follow-on:** the in-Rego `some`/`every` match is the bridge to **Phase 7** (ReBAC-in-Rego — pushing the
  relationship match into the policy). Declarative tag auto-population and subject-tag-vs-resource-tag
  matching are documented future variants.

## Related
- ADR 0003 (the role definition this extends) · ADR 0002 (team-target, which scopes team keys) · ADR 0001 (entity graph)
- [[TAG-DICTIONARY]] (the shipped slice) · [[TAG-BASED-AUTHORIZATION]] (the guide) · [[RESEARCH-AUTOTAG-AND-FILTERING]] (study background)
