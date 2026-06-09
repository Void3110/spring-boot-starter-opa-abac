---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/opa
  - area/spring-data
---

# ADR 0010 — Hierarchy-aware list filter (the `subtreeSpec` composition)

**Status:** Accepted (planned)
**Date:** 2026-06
**Context tags:** ABAC, OPA, partial-eval, list filtering, ancestor inheritance, fail-closed, ltree, SPI

> This ADR pins the **list-specific forks** that [[0008-hierarchical-resource-authorization|ADR 0008]]
> deferred to "Slice B, with its own ADR if a fork surfaces." It governs **how an ancestor grant widens
> the rows a list returns** — the composition `combined = scope.and(tagResidual.or(subtreeSpec)).and(notDenied)`
> made concrete: where `subtreeSpec` comes from, where it is composed, how deny-overrides becomes a SQL
> predicate, and how the new subtree resolution rides the `AncestorResolver` SPI. It **extends** ADR 0008
> (does not supersede it); ADR 0008 still governs the single-resource walk and the overall hierarchy model.

> **Numbering note.** ADR 0008 §Consequences reserved "ADR 0009" for this fork; **0009 was subsequently
> taken** by [[0009-tag-requirement-subject-side|the tag-requirement decision]], so the hierarchy-list fork
> is **0010**. ADR 0008's forward-reference is corrected to point here.

## Context

[[0008-hierarchical-resource-authorization|ADR 0008]] shipped as two slices. **Slice A (5.5-A, done)**
delivered the single-resource hierarchical decision: the `AncestorResolver` SPI (ltree + recursive-CTE
impls, cycle/depth fail-closed), `HierarchicalAuthorizer` (resolve chain → role once on the governing root
→ OPA `final_allow = (direct OR inherited) AND NOT denied`), `AbstractHierarchicalEntity` (ltree `path` +
atomic re-parent), and the Rego inheritance + deny-overrides clause. **Slice B (5.5-B, this ADR)** makes
the **list** path hierarchy-aware.

ADR 0008 already fixed the spine: the OPA partial-eval residual **stays tag-only** (the hardened
`CompileResponseParser` / `ResidualSpecificationFactory` / closed operator set are untouched), and
hierarchy widening is a **separate app-built `subtreeSpec`** OR-ed in:

```
combined = scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )
```

What ADR 0008 left open — and what authoring Slice B surfaced as real structural forks — is **four
concrete questions** the formula hides:

1. **Where do `subtreeSpec`'s allowed-subtree-roots come from** for a list (which has no leaf to walk up
   from)?
2. **Where is the OR/AND composition owned**, without breaking the byte-compatible tag-only callers?
3. **What is `notDenied` in SQL**, given single-resource does deny-overrides in Rego?
4. **How does the new "select the subtree" operation ride the `AncestorResolver` SPI** (which today only
   walks *up*), and how does it compose with the `not-fullySupported` allowlist-batch path?

These are expensive to reverse (they shape a public SPI, the `AbacQueryService` seam, and the fail-closed
posture of every list call) and surprising to a future reader — hence this record. The shipped seams they
constrain: `AbacQueryService.findAuthorized(repo, scope, queryContext)`; `AncestorResolver.ancestorsOf`;
the per-row `batchFilter`/`withResource` allowlist finisher; the Rego leaf-deny `abac_deny == true`.

## Decision

Six pinned choices.

### 1. Allowed-subtree-roots are **root-only**: `{governing root}` or `{}`

For a list, the only candidate subtree-root is the **governing root of the list's scope** (e.g. the
`catalogId` the list is under). If the subject's role — **resolved once on that root**, exactly as
`HierarchicalAuthorizer` and the existing app list-authorizer do — **inheritably** grants the verb, then
`subtreeSpec` selects that root's **whole subtree**; otherwise `subtreeSpec` is **empty (false)**. So
`subtreeSpec` is binary: *whole governing-root subtree* or *nothing*.

**Mid-tree per-node grants** (a grant on an intermediate Category widening only its sub-subtree) are **out
of scope** → Phase 8 / ReBAC, consistent with ADR 0008 §2 ("role resolved once on the governing root").
This is the faithful list-analogue of Slice A: a row is widened-in iff it is in the governing-root subtree
**and** the root-resolved role inheritably grants the verb — exactly the condition
`HierarchicalAuthorizer.isAllowed` returns `true` on for any single row in that subtree. List and single-GET
agree by construction (ADR 0005/0006 consistency). **Inheritance is opt-in** (ADR 0008 §3): if the
relation is not declared inheritable, `subtreeSpec` is always empty and the list degrades to today's
tag-only result — the fail-closed default.

### 2. A `SubtreeSpecResolver` owns the resolution; an additive 4-arg overload owns the composition

- **`SubtreeSpecResolver`** (new, `opa-abac-spring-data`) holds the `AncestorResolver` +
  `RoleDefinitionSupplier`. Given `(subject, governingRoot, verb)` it: resolves the role on the root,
  applies the inheritable-relation gate, and — if granted — returns the subtree predicate by calling the
  SPI (choice 4); otherwise `Optional.empty()`. This is where the root-only logic (choice 1) and the
  ltree-vs-CTE knowledge live, **behind the SPI**.
- **`AbacQueryService` gains an additive overload** `findAuthorized(repo, scope, queryContext, subtreeSpec)`
  that owns the composition `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)`. The **existing 3-arg
  signature is preserved byte-compatible** — it delegates with `subtreeSpec = null` (→ exactly today's
  tag-only behavior). `AbacQueryService` receives a **ready `Specification`**, so it gains **no**
  `AncestorResolver`/`RoleDefinitionSupplier` dependency on the composition path; it stays the single owner
  of "AND, never replace."

The "AND, never replace" + "OR-in the widening" + "AND-out the deny" composition is the fail-closed-sensitive
core, so it lives in **one place** (the service that already owns the residual-AND invariant), not scattered
into a wrapper or the example app.

### 3. `notDenied` is the SQL mirror of the shipped leaf deny, AND-ed **outside** the OR

The shipped Rego deny is a single closed predicate: `denied if input.resource.attributes.abac_deny == true`
(`category.rego`). The list `notDenied` is the **SQL form of that same predicate** —
`abac_deny IS DISTINCT FROM true` over the row's `tags` JSONB column — AND-ed **outside** the OR so it
narrows **both** the tag-branch and the subtree-branch:

```
combined = scope.and( tagResidual.or(subtreeSpec) ).and( abac_deny IS DISTINCT FROM true )
```

Deny **cannot** live inside the OR-ed `tagResidual` (the `subtreeSpec` branch would re-admit a denied row →
deny would stop overriding the inherited widening). Fail-closed: a row **absent** the deny tag →
`NULL IS DISTINCT FROM true` → `TRUE` → not denied — which matches Rego's `not denied` on an absent key.
Single-GET and list agree on what "denied" means. Because `abac_deny` is a closed scalar boolean tag it is
always SQL-expressible; a **future, non-scalar deny** the SQL cannot express must **not** be silently
treated as `TRUE` (that is fail-open) — the fail-closed rule is to route such a row through the per-row
allowlist batch (choice 5), where the full Rego deny applies. B only ships the scalar `abac_deny` form.

### 4. The subtree predicate rides the SPI as `subtreeOf(rootType, rootId) → Specification`

`AncestorResolver` gains **one additive method** that returns a **`Specification` predicate**, *not* an id
set — each impl produces it its own way:
- **ltree (default):** `path <@ '<root-label>'` — a pure SQL pushdown; the descendant id set is **never
  materialized** in Java (preserving ltree's whole advantage).
- **recursive-CTE:** `id IN (<descendant ids>)` — a downward `parent_id` walk, **bounded by the same
  `maxDepth`** guard from Slice A, **fail-closed to an always-false predicate** on depth breach / cycle /
  SQL error (→ the list falls back to the narrower tag-only result, never wider).

Returning a `Specification` (not `descendantIdsOf → Set<id>`) keeps the ltree pushdown intact and keeps the
ltree-vs-CTE divergence **behind the SPI** (ADR 0008 §5) — the caller gets a predicate and never learns
which strategy produced it. `Specification` (a Spring Data type) on the SPI is fine: `AncestorResolver`
lives in `opa-abac-spring-data`, **not** `opa-abac-core` (the core's Spring-free boundary is untouched).

### 5. The allowlist-batch path becomes hierarchy-aware; `subtreeSpec` applies to the SQL path only

`AbacQueryService`'s `not-fullySupported` allowlist branch builds per-row contexts and calls
`opaClient.allowAll`. Slice B changes `batchFilter`/`withResource` so each per-row `AbacContext` carries the
row's **ancestor chain** (4-arg `Resource(type,id,attributes,ancestors)`), so the per-row OPA decision is
the **same** `final_allow = (direct OR inherited) AND NOT denied` as the single-GET. Consequences:
- The batch path is **hierarchy-correct and deny-correct natively** — `subtreeSpec` is **not** applied
  there (it would be redundant; the per-row Rego decision already includes inheritance and deny).
- `subtreeSpec` (choice 4) is applied **only** on the pure-SQL path.
- Resolving each candidate's ancestors is **cheap**: candidates are `AbstractHierarchicalEntity` rows whose
  ltree `path` is already loaded, so the ltree resolver derives ancestors from the in-memory column with no
  extra query.
- Fail-closed holds: a per-row resolution failure → empty ancestors → that row decided on its **direct**
  grant only (never wider); a short/all-false `allowAll` still drops rows.

This costs a small change to the shipped `AbacQueryService` internals (the batch path gains an
`AncestorResolver`), accepted to keep the SQL path and the batch path **consistent** (same subject + data →
same row set regardless of which branch the residual takes).

### 6. Scope is still AND-ed first; the widening never escapes the caller's scope

`subtreeSpec` is OR-ed with `tagResidual` **inside** `scope.and(...)`. A subtree grant widens which rows
within the caller's `catalogId` scope are returned — it can **never** surface a row outside that scope (no
cross-catalog leak). This is the AND-with-scope no-leak invariant from the Phase-5 seam, preserved verbatim
and asserted in an IT (subtree widening + a foreign scope → empty).

## Considered options

| Option | Why not |
|--------|---------|
| **Mid-tree per-node subtree roots** (a grant on an intermediate node widens its sub-subtree) | Requires per-node grant resolution — that **is** ReBAC (Phase 8) and contradicts ADR 0008 §2 (role resolved once on the governing root). Root-only needs **zero** new resolution machinery (reuses the existing root role lookup) and still agrees with the single-GET. |
| **A self-contained `HierarchicalQueryService` wrapper** that owns `subtreeSpec` *and* re-implements the compose, leaving `AbacQueryService` 100% untouched | Splits the fail-closed composition (`AND`/`OR`/deny) across two services and duplicates the "AND, never replace" invariant. The composition belongs next to the existing residual-AND, in one place. We keep `AbacQueryService` as the composition owner via an **additive overload** instead. |
| **App-side-only `subtreeSpec`** (the example's `CategoryListAuthorizer` computes the predicate; the library just OR-s a caller-supplied spec) | Pushes the inheritable-grant + subtree-predicate logic — which the library already encapsulates for the single case in `HierarchicalAuthorizer` — into the example app, duplicating library-grade logic in a demo. The resolution is library-grade; it stays in `SubtreeSpecResolver`. |
| **Fold deny into the tag residual** (`tagResidual` already `AND NOT denied`) | The residual is OR-ed with `subtreeSpec`; a deny inside the OR lets the subtree branch **re-admit** a denied row → deny stops overriding. Deny must be a **separate outer AND**. |
| **Defer deny-overrides on lists entirely** (`notDenied = TRUE` for B) | Cheaper, but B's e2e then cannot show the headline "an ancestor grant widens the list **yet** an explicit deny still removes a row" — the single most persuasive demonstration of opt-in-inheritance + deny-overrides composed. The deny is one closed scalar predicate; building it is small. |
| **`descendantIdsOf(type,id) → Set<id>` on the SPI** | Forces **even the ltree impl** to materialize the id set, discarding its `path <@` pushdown (the reason ltree is the default). A `Specification`-returning `subtreeOf` lets each impl push down or enumerate as fits. |
| **Teach the OPA residual an `ltree` operator** (emit `path <@` inside the residual — ADR 0008's Option A) | Already rejected by ADR 0008: couples the hardened closed operator set to a lineage column and grows the Compile-AST parser. Hierarchy stays an app-built `subtreeSpec`. |
| **Keep the allowlist-batch path tag-only** (non-hierarchical per-row decision) | Fail-closed (it only removes rows) but **inconsistent**: the same subject+data would get a different row set depending on whether the residual happened to be fully-SQL. Making the batch path carry ancestors keeps both paths on the same Rego decision. |
| **Split B into two slices** (SQL-path widening; then batch + deny + e2e) | The widening (`subtreeSpec`) and the deny narrowing (`notDenied`) are one **atomic correctness unit** — shipping the widening without the deny override mid-slice would be fail-open. They go together; B stays one slice. |

## Consequences

- **Good:** an ancestor grant **widens a list** in SQL (the headline gap after partial-eval); the
  composition is owned in one fail-closed place; the OPA residual + closed operator set stay **untouched**
  (ADR 0008's promise kept); the ltree-vs-CTE choice stays an **SPI decision** the app makes; the list and
  the single-GET decide the **same rows** (consistency), now including the allowlist-batch path; tag-only
  callers are **byte-compatible** (additive overload). `opa-abac-core` stays Spring-free.
- **Cost:** a new public SPI method (`subtreeOf`) both shipped resolvers must implement (and any custom
  resolver in the wild); a change to the shipped `AbacQueryService` batch internals (gains an
  `AncestorResolver` on that path); a `SubtreeSpecResolver` + a `notDenied` Specification + the example
  list-authorizer adoption. All additive; no breaking change.
- **Boundary:** mid-tree per-node grants, an independent team on an intermediate node, and any non-scalar
  deny remain **deferred** (Phase 8 / a future deny slice). B ships only the scalar `abac_deny` deny and the
  root-only subtree widening.

## Related

- ADR [[0008-hierarchical-resource-authorization|0008]] (the hierarchy model this extends — its §"How it
  composes with the Phase-5 list filter" is the formula this ADR makes concrete; its line-156
  forward-reference is corrected to point here).
- ADR [[0005-partial-eval-to-jpa-specification|0005]] (the tag-only residual kept untouched) ·
  ADR [[0006-three-layer-enforcement-model|0006]] (this completes layer 3's hierarchy-awareness) ·
  ADR [[0009-tag-requirement-subject-side|0009]] (took the number ADR 0008 had reserved).
- [[PARTIAL-EVALUATION-FILTERING]] (the shipped filter this composes with) ·
  [[HIERARCHICAL-AUTHORIZATION]] (the single-resource decision this mirrors for lists) ·
  [[POC-ROADMAP]] (Phase 5.5-B) · [[USER-STORIES]] (the hierarchy epic).
