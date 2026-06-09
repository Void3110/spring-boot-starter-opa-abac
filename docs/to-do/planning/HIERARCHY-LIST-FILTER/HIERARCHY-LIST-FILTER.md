---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/spring-data
---

# Hierarchy-aware list filter (Slice 5.5-B)

> 🟡 **PLANNED** — branch `feature/void3110/hierarchy-list-filter`. The **second** of two slices for
> [[POC-ROADMAP]] **Phase 5.5**, pinned by ADR [[0008-hierarchical-resource-authorization|0008]] and the
> list-specific ADR [[0010-hierarchy-aware-list-filter|0010]]. Builds directly on the **shipped**
> [[HIERARCHY-SINGLE-RESOURCE|5.5-A]] resolver and the **shipped** [[DATA-FILTERING|Phase-5]] partial-eval
> filter — it composes the two so an **ancestor grant widens which rows a list returns**.
>
> Slice 5.5-A made a **single** deep check (`GET …/products/{id}`) consider the whole ancestor chain. This
> slice makes a **list** (`GET …/categories`, `…/products`) do the same: a subject who can't see rows by
> their own leaf-tags **does** see them when an inheritable grant on the governing root includes the
> subtree — proven as a per-subject SQL row-set difference through the gateway.

This package mirrors the six shipped slices ([[DOMAIN-MODEL-FOUNDATION]], [[LIBRARY-SPINE]],
[[USER-MANAGEMENT-SERVICE]], [[TAG-DICTIONARY]], [[DATA-FILTERING]], [[HIERARCHY-SINGLE-RESOURCE]]) 1:1 in
structure. The design half (this index + [[00-DESIGN]]) is written from a settled **ADR 0008 + ADR 0010**;
the decomposition half (`01-DECOMPOSITION` + `10-QA-TEST-CASES` + the autonomous prompt + STATUS stubs) is
produced by the **slice-planner** skill.

## Why this slice

After [[DATA-FILTERING|partial-eval data filtering]] (Phase 5) and [[HIERARCHY-SINGLE-RESOURCE|single-resource
hierarchy]] (5.5-A), one gap remains: a **list** endpoint still cuts rows by the **row's own tags only**. An
ancestor grant that authorizes a single `GET …/products/{id}` three levels down does **not** yet widen the
corresponding `GET …/products` list — the two decisions disagree. This slice closes that gap so the list
and the single-GET decide the **same rows**, including when the grant is inherited from an ancestor.

> **The differentiator.** Partial-eval row filtering is rare in Spring-native OPA integrations;
> **hierarchy-aware** row filtering (an ancestor grant widening a list, pushed into SQL, fail-closed) is
> rarer still. This is the capstone of the hierarchy story.

## The core idea (pinned by ADR 0010)

The shipped OPA residual is a DNF over the **row's own JSONB tags**; "is this row in an allowed subtree" is
a fact about **lineage**, not tags. So the residual **stays tag-only** (the hardened
`CompileResponseParser` / `ResidualSpecificationFactory` / closed operator set are **untouched**), and
hierarchy widening is a **separate, app-built `subtreeSpec`** OR-ed into the query:

```
combined = scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )
```

- **`subtreeSpec`** = "the rows in the governing root's subtree" — **but only when** the subject's role,
  resolved once on that root, **inheritably** grants the verb; otherwise empty. (Root-only; mid-tree
  per-node grants are Phase 8.)
- **`notDenied`** = the SQL form of the shipped leaf deny (`abac_deny == true`), AND-ed **outside** the OR
  so a deny still overrides an inherited widening.
- **Fail-closed:** a failed hierarchy resolution → an empty/false `subtreeSpec` → the result falls back to
  the **narrower** tag-only filter — never wider.

## What this slice delivers

In the library:

- **`opa-abac-spring-data`** —
  - a new **`AncestorResolver.subtreeOf(rootType, rootId) → Specification`** SPI method (additive) — the
    **ltree** impl returns a `path <@ <root-label>` SQL pushdown (id set never materialized); the
    **recursive-CTE** impl returns `id IN (<descendant ids>)` from a downward walk, **bounded by `maxDepth`,
    fail-closed to an always-false predicate** on breach/error. (ADR 0010 §4.)
  - a new **`SubtreeSpecResolver`** — given `(subject, governingRoot, verb)` it resolves the role on the
    root, applies the **inheritable-relation gate**, and returns the `subtreeSpec` (or empty). This is where
    the root-only logic + the ltree/CTE choice (behind the SPI) live. (ADR 0010 §1–2.)
  - an **additive 4-arg `AbacQueryService.findAuthorized(repo, scope, queryContext, subtreeSpec)`** that
    owns the composition `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)`; the **existing 3-arg
    signature is preserved byte-compatible** (delegates with `subtreeSpec = null`). (ADR 0010 §2.)
  - a **`notDenied` Specification** (`abac_deny IS DISTINCT FROM true` over the tags JSONB) AND-ed outside
    the OR; mirrors `category.rego`. (ADR 0010 §3.)
  - the shipped **allowlist-batch path becomes hierarchy-aware** — `batchFilter`/`withResource` build per-row
    contexts **with the row's ancestor chain**, so `opaClient.allowAll` decides each row by the same Rego
    `final_allow` as the single-GET. `subtreeSpec` applies to the **pure-SQL path only**. (ADR 0010 §5.)
- **`opa-abac-spring-boot-starter`** — wire the `SubtreeSpecResolver` bean (conditional + overridable); the
  inheritance declaration + `maxDepth` config already exist from 5.5-A.

In the example + infra:

- **catalog** — `CategoryListAuthorizer` (and the product list path) call the **4-arg** `findAuthorized`,
  resolving the role on the governing Catalog exactly as today, passing the `subtreeSpec` resolved from the
  subject's inheritable catalog grant. A re-parent operation (already exposed by 5.5-A) is exercised against
  the **list**.
- **`category.rego`** — the `filter` rule's relationship to inheritance is confirmed/aligned (the per-row
  batch decision already reuses the shipped `final_allow`); `opa test`.
- an **e2e** matrix proving (through the gateway): an **ancestor grant widens a list**; **two subjects →
  different subtree row sets**; **deny-overrides removes a row** from a widened list; the **unbound stranger
  gets `[]`** (fail-closed); and a **re-parent moves a row in/out** of a subject's visible list.

## What this slice does NOT do (held for later)

- **Mid-tree per-node grants** (a grant on an intermediate Category widening only its sub-subtree) — the
  subtree-root set is **root-only** (`{governing root}` or `{}`). Per-node independent grants are **Phase 8**
  (ReBAC-in-Rego). (ADR 0010 §1.)
- **Any change to the shipped tag-only residual / operator set** — untouched; hierarchy is a separate
  app-built `subtreeSpec`. (ADR 0008 + 0010.)
- **Non-scalar deny shapes** — B ships only the scalar `abac_deny` deny in SQL; a deny SQL can't express
  routes to the allowlist batch (documented), and a richer deny model is a future slice. (ADR 0010 §3.)

## File glossary

| File | Role |
|------|------|
| `HIERARCHY-LIST-FILTER.md` | This index — what the slice delivers, the glossary, the ticket status table, conventions. |
| `00-DESIGN.md` | The design: the `subtreeSpec` composition, the `subtreeOf` SPI method (ltree pushdown / CTE bounded walk), `SubtreeSpecResolver`, the 4-arg overload, `notDenied`, the hierarchy-aware batch path, the fail-closed story, considered-&-rejected. |
| `01-DECOMPOSITION.md` | The ordered tickets (Goal / Deliverables / Acceptance / What-NOT-to-touch) + the critical path. **The work list.** *(produced by slice-planner)* |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | The self-contained prompt. *(produced by slice-planner)* |
| `10-QA-TEST-CASES.md` | Concrete unit / integration / e2e cases. *(produced by slice-planner)* |
| `STATUS-0N.md` | One per ticket, filled at each checkpoint during the autonomous run. *(produced by slice-planner)* |

## Ticket status

> Provisional (≈6 tickets; firmed up by slice-planner in `01-DECOMPOSITION`).

| # | Ticket | Module | Status |
|---|--------|--------|--------|
| T1 | SPI: `AncestorResolver.subtreeOf` + both impls (ltree `path <@` pushdown · CTE bounded `id IN`, fail-closed) + ITs | spring-data | ✅ |
| T2 | `SubtreeSpecResolver` (root-role resolution + inheritable gate → `subtreeOf`; fail-closed to empty) | spring-data | ✅ |
| T3 | `AbacQueryService`: 4-arg overload (OR/AND composition) + `notDenied` Spec + hierarchy-aware batch path; 3-arg byte-compat | spring-data | ✅ |
| T4 | spring-data IT: both impls' row-sets, `notDenied` narrowing, AND-with-scope no-leak, re-parent in/out (Testcontainers) | spring-data | ✅ |
| T5 | Starter wiring (`SubtreeSpecResolver` bean) + example list-authorizer adoption (4-arg call) | starter + example | ✅ |
| T6 | e2e matrix (widen · two-subjects · deny-removes · stranger-empty · re-parent flip) + docs + roadmap/Mulch | e2e + docs | ⬜ |

**Critical path:** T1 → T2 → T3 → T5 → T6, with **T4 (IT)** landing after T3 (it proves T1–T3 against real
Postgres before the example adoption). T1 is independently landable (pure SPI + ITs, no app).

## Conventions (same as every prior slice)

- **Clean-room IP boundary.** Original neutral names only; the prior platform is **study-only**. Never copy
  proprietary source/names/paths.
- **`opa-abac-core` stays Spring-free** — all of B's work is in `opa-abac-spring-data` / starter / example;
  the core is **not touched** (the `Specification` on the SPI is a Spring Data type, allowed in spring-data).
- **Fail-closed everywhere** — a failed/too-deep subtree resolution → an empty (false) `subtreeSpec` → the
  **narrower** tag-only result; `notDenied` is never silently `TRUE` for an inexpressible deny (route to the
  allowlist batch); the batch path only ever **removes** rows. Inheritance stays **opt-in, default-off**.
- **Additive / byte-compatible** — the 3-arg `findAuthorized` is preserved; the `subtreeOf` SPI method is
  additive (both shipped impls implement it); the residual model / operator set / `RoleDefinition` are
  **unchanged**.
- **The residual stays tag-only** — no new OPA-wire machinery; hierarchy is an app-built `subtreeSpec`.
- **Commit identity** `Void3110 <void31102025@gmail.com>`; **one focused commit per ticket**; **do not
  push**. Mulch sync commits touch `.mulch/` only.

## Related

- ADR [[0010-hierarchy-aware-list-filter|0010]] — the **list-specific** decision this slice implements
  (`subtreeSpec` composition, `subtreeOf` SPI, deny-as-SQL, hierarchy-aware batch).
- ADR [[0008-hierarchical-resource-authorization|0008]] — the hierarchy model; this is its Slice B.
- [[HIERARCHY-SINGLE-RESOURCE]] — the shipped Slice A whose resolver this composes.
- [[DATA-FILTERING]] / [[PARTIAL-EVALUATION-FILTERING]] — the shipped Phase-5 filter this extends (residual
  kept untouched).
- ADR [[0005-partial-eval-to-jpa-specification|0005]] · [[0006-three-layer-enforcement-model|0006]] —
  the partial-eval residual + the three-layer model this completes (layer 3 hierarchy-awareness).
- [[POC-ROADMAP]] — Phase 5.5 (this slice closes it); Phase 8 (ReBAC — mid-tree per-node grants).
- [[USER-STORIES]] — Epic H, story **H5** (the hierarchy-aware list story this slice delivers).
