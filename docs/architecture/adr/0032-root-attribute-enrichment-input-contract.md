---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/opa
  - area/spring
---

# ADR 0032 — Root-attribute enrichment: the `input.resource.root_attributes` contract

**Status:** Accepted — planning; implemented by slice **B** (`PRODUCTION-TIER`)
**Date:** 2026-08-07
**Context tags:** root attribute enrichment, input contract, fail-closed three-state, tag inheritance

> [[0030-step-up-decision-contract|ADR 0030]] §4 decided *that* the governing root's attributes reach
> child decisions by a per-request, memoized fetch — not by widening `ParentRef` or loading ancestor
> entities. This ADR pins the **contract** of the field that carries them, because it is a change to
> the published OPA input every adopter sees, and because its failure semantics are load-bearing for
> the production tier.

## Context

Tags are **leaf-scoped**: `input.resource.attributes` on a product is the product's own tag map, and
nothing inherits (ADR 0009). The production tier ([[0030-step-up-decision-contract|ADR 0030]] §3) is a
tag on the governing **root**, so child-read decisions need the root's tags in the input. Whatever
field carries them must let policy distinguish "the root has no tags" from "we could not establish the
root's tags" — collapsing those two states would make an enrichment outage indistinguishable from an
untagged (and therefore non-production, §3) catalog: a fail-open.

## Decision

`AbacContext.Resource` gains a **fifth, additive component**:

```java
public record Resource(
        String type,
        String id,
        Map<String, Object> attributes,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ParentRef> ancestors,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("root_attributes") Map<String, Object> rootAttributes) { … }
```

serialized as `input.resource.root_attributes`, with the evolution pattern the `ancestors` component
established: compat constructors keep every existing caller compiling unchanged, and an absent value
serializes **byte-for-byte as today**, so no existing consumer, policy, or recorded fixture changes.

**Three states, all distinguishable in policy — the heart of the contract:**

| Wire state | Meaning | Tier consequence (ADR 0030 §3) |
|---|---|---|
| key **absent** | enrichment failed, or the caller never attempts it | tier **unproven** → the supervised path treats it as closed |
| `{}` (present, empty) | the root was fetched and has **no tags** | untagged → **non-production** |
| `{"env": "production", …}` | the root was fetched and is tagged | as tagged |

This is why the annotation is **`NON_NULL`, not `NON_EMPTY`**: under `NON_EMPTY` an untagged root's
empty map would vanish from the wire and become indistinguishable from a failed fetch, silently
merging the two states this contract exists to separate.

**The field carries the root's full tag map**, not `env` alone — the mechanism is generic enrichment;
the tier is merely its first consumer.

**Population: the authorization manager resolves the governing target it already computes, through
the application's existing resolver SPI.** *(Amended 2026-08-07 at decomposition, verified against
source: the SPI's implementors are the JPA entities themselves — `AbstractSecuredEntity` is the only
`implements AbacResource` in the main tree — and an entity holds no root reference, so the
originally-planned `rootAttributes()` default method had nothing to fetch with; a resolver-side
wrapper would break the manager's typed write-through cache; and the type-level list gates have no
instance to call a default method on at all.)* The mechanism, one rule for both paths:

- **Instance path** (`resolveInstance`): the governing root is already computed —
  `ancestors.isEmpty() ? leaf : ancestors.get(0)`. When it is **distinct from the leaf**, the manager
  resolves it via `resolutionSupport.resolver().resolve(rootType, rootId)` and uses its
  `abacAttributes()` as `rootAttributes`. When the leaf **is** the root, the field stays absent —
  the leaf's own attributes already carry its tags, and a duplicate would invite policies to gate a
  root's *own* read on "root" attributes (ADR 0030 §1 keeps root metadata ungated).
- **Type-level path**: when a `roleResourceType`/`roleResourceId` override is declared and resolves
  (the child list gates), the manager resolves the **override target** the same way and threads its
  attributes; no override → absent.
- **Any failure** — resolver returns empty, throws, or `resolutionSupport` is not configured — leaves
  the field **absent** (state one of the table above). The root resolve is read-through-memoized in
  the existing `RequestAttributesResourceCache`, so a request pays at most one extra resolver call.

The library still fetches only through the app's resolver SPI — exactly as it already fetches the
leaf instance; which entity a `(type, id)` names stays entirely with the application.

## Consequences

**Good.** Policy can gate on ancestor state without tag inheritance, entity loads in the ancestor
resolvers, or any change to `ParentRef`. The failure mode is a *narrower* result by construction:
absent ⇒ unproven ⇒ the gated path closes, while paths that never consume the field (every membership
decision) are untouched. Old inputs are byte-identical, so the change is invisible until a resolver
opts in.

**Costs.** The published input schema grows a field whose three-state semantics must be documented
wherever policy authors read (`ABAC-AUTHORIZATION` guide); a policy that tests it with a bare
`not root_attributes.env == "production"` reads naturally but is **wrong** (absent env passes a
negated comparison in Rego) — the shipped clauses and their tests are the reference shape.

**Rejected.** Widening `ParentRef` with attributes (forces entity loads on the optimized ancestor
path; changes a published record plus its supplier SPI — ADR 0030 §4 already declined this);
carrying root attributes inside `input.resource.attributes` under a reserved key (collides with the
leaf's own tag namespace, which is client-influenced); **an `AbacResource.rootAttributes()` default
method** (the original plan — rejected at decomposition when verified against source: the
implementors are entities with no root reference, a wrapper breaks the typed write-through cache,
and the type-level gates have no instance; see the amended Population section); a separate
enrichment SPI interface (the manager already owns the governing-target computation — a second seam
would duplicate it).
