---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/catalog-service
  - area/api
---

# ADR 0033 — The `_provenance` affordance on catalog rows

**Status:** Accepted — planning; implemented by [[SPA-CHALLENGE-UX]] (T1)
**Date:** 2026-08-15
**Context tags:** affordance metadata, `_provenance`, supervised read path, two-leg list, absent-when-not-computed, example-side advice

> [[0029-supervised-read-scope|ADR 0029]] gave a supervisor a second, disjoint access path and made
> its provenance a **role attribute** the policy and the audit can read. Nothing on the wire tells a
> client *which* rows it holds by supervision. This ADR pins the field that does — its meaning, its
> two derivations, and its absence semantics — because it joins `_actions` as a published affordance
> on the example's contract, and because a lying default here would let a client predict wrongly
> about the very rows the second factor guards.

## Context

The catalog list is a **two-leg** query (`CatalogListAuthorizer`): membership rows `M` and
supervised rows `S \ M`, membership always winning. The per-row leg is known at query time (it
already feeds an audit line) and discarded at the return. On the single-catalog GET there is no leg
— the resolved role carries `attributes.provenance ∈ {"membership", "supervised"}` (the ADR 0031
stamp), computed by the manager and read only by the privileged-read audit. Categories and products
have no provenance of their own: they inherit their catalog's.

The demo SPA needs to explain and predict: label supervised rows, and predict "this production
catalog will ask for a second factor" *before* the click. Predicting from the token alone is
impossible (the `unit-supervisor` claim is an eligibility marker, never resolver input — ADR 0029);
predicting from `tags.env` alone would mark a **member's** production catalog, which needs no
elevation (K13). The client needs the server's answer to "by which path is this row in front of
you?".

## Decision

1. **The field.** `Catalog` gains an additive, optional, read-only **`_provenance`** with the
   vocabulary **`"member"` | `"supervised"`** — on **catalog list items and the single-catalog GET**
   only. Categories and products carry nothing; a client propagates the catalog's value on drill-in.
2. **It is an affordance, not enforcement** (the [[0016-action-enrichment-affordance-metadata|ADR 0016]]
   stance, verbatim). A client uses it to explain and to *predict*; every decision remains the
   server's. Predicted-and-wrong is a UI bug, never a security event.
3. **One semantic, two derivations, pinned to agree.** On the **list** the value comes from the leg
   (id ∈ the supervised set ⇒ `"supervised"`, else `"member"`). On the **GET** it comes from the
   stamp of the role the gate resolved on the catalog (`supervised` ⇒ `"supervised"`, `membership` ⇒
   `"member"`). They agree by construction — the user-service synthesizes the supervised role only on
   the non-membership branch (ADR 0029), so a catalog is in `S \ M` iff its role is stamped
   `supervised` — and an integration case pins the agreement on both personas.
4. **Absent when not computed — never `null`, never a default.** Omitted when the server did not
   establish the value: on the GET, a role lookup that throws, returns no stamp, or a stamp outside
   the vocabulary; on the list, a request that never passed through the two-leg authorizer's query
   path (no memo attribute). **Present-but-empty is not absent**: the list memo is the supervised id
   set *including the empty set* — an empty set means every row is membership and each row is
   labeled `"member"`; the advice tests attribute presence, never emptiness, and the authorizer
   writes the memo unconditionally before its query (not inside the early-returning audit method). Every **degrade branch that still labels honestly does label**: an agent-marked call
   (supervised leg skipped — the rows are membership, `"member"`), a membership-leg outage (the page
   is supervised-only, `"supervised"`), a supervised-source outage (an empty supervised set, the
   survivors `"member"`). Recorded limitation U42 (mixed subject, partial-eval off) does not affect
   the label — it reads the id set, not the judging role.
5. **The mechanism is the `_actions` one, example-side.** A second marker interface appended to the
   `Catalog` schema's `x-implements` list, declaring the getter with `@JsonInclude(NON_NULL)` (the
   only way a scalar buys absence — the generated DTO carries no inclusion annotation and there is no
   global setting); a `ResponseBodyAdvice` mutating the DTOs in place after the page envelope exists;
   the supervised id set carried from the authorizer to the advice as a **request attribute** (the
   repo's `RequestContextHolder` idiom). The spec declares the value `type: string` with the
   vocabulary in its `description` and **no `enum:` key**: under this generator an inline `enum:`
   — even one intended as documentation — produces a per-DTO nested enum type (`ProblemDetail`'s
   `ErrorCodeEnum` is the live precedent) that cannot be a shared interface getter's return type,
   and no generator option suppresses it. The single-body derivation runs **on the GET handler
   only**: `createCatalog` also returns a bare `Catalog`, but its gate is type-level (`null`
   resource id), so a lookup by the new id would be a guaranteed memo miss and a real round-trip on
   every create. **No library change**: `Enrichable` gains no domain noun; the
   manager exposes no role.
6. **The GET's cost is documented, not engineered around**: the derivation is a role re-lookup that
   is a request-memo hit under the default-on `opa.abac.resolve-memo.enabled`; with the memo off it is
   one extra round-trip per GET. The example ships memo-on.

## Consequences

- Clients can label and predict honestly; a member's production catalog is never marked
  "verify to open"; an absent field means "unknown", not "member".
- The wire contract of `Catalog` grows by one optional key; every existing consumer ignores it.
- A future library seam (a "resolved role" request attribute set by the manager) would let the GET
  derivation stop re-looking-up; it is additive and its own slice — this ADR does not require it.
- If a third access path ever appears (a tiered/elevated role, say), the vocabulary grows by a value
  and the two derivations gain one branch each; the absence semantics do not change.

## Alternatives considered

- **`_supervised: true` (boolean, absent otherwise)** — rejected: absence would mean both *member*
  and *not computed*, collapsing the honesty distinction `_actions` established.
- **`_provenance` on categories and products too** — rejected: children cannot differ from their
  catalog; redundant serializer work with no new information.
- **Deriving both paths from the role stamp** — rejected: per-row role lookups on the list would
  reintroduce the per-row resolve amplification Phase 7.3 removed; the leg *is* the list's truth.
- **A library-side request attribute for the resolved role** — deferred: a library change for a
  demo affordance; the memoized re-lookup is free on the default config.
