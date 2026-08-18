---
tags:
  - status/active
  - type/architecture
  - area/abac
  - area/methodology
---

# ReBAC and ABAC — what this library's model actually is

> **The one-line claim:** this repo implements **a relationship-shaped model evaluated through an
> attribute mechanism.** Both halves are load-bearing, and the docs said only the second half for a
> long time.

The artifact is called `spring-boot-starter-opa-abac` and brands ABAC throughout, which is accurate
about the *mechanism* and quietly incomplete about the *model*. A reader who knows Zanzibar will spot
the relationship spine in five minutes; naming it first is simply more honest, and it makes the
comparison below possible.

Every external claim on this page was read from the other project's own documentation. Sources are
linked inline.

## What actually decides an access here

| Mechanism | What it really is |
|---|---|
| **Membership is the sole access path** to a catalog ([[adr/0018-team-scoped-resource-isolation\|ADR 0018]]) | a **relationship** predicate — user → team → resource |
| **N-level hierarchy**, an ancestor grant widening a descendant list ([[adr/0008-hierarchical-resource-authorization\|ADR 0008]]) | **graph traversal** — an ltree ancestor walk |
| The **supervised** read path ([[adr/0029-supervised-read-scope\|ADR 0029]]) | a **relationship two hops out** — manager → report → team → resource |
| Tags + `ANY_OF`/`ALL_OF` matching | genuine **attributes** |
| Permission categories + expansion | genuine **attributes** |
| The `env` production tier + `auth_time` freshness | genuine **attributes**, including environment and authentication context |

The **access spine is relational**. Tags, permission categories and the tier are attributes that
*refine* what a relationship already opened — they are not what opens it. That is the shape ReBAC
describes.

## The useful question is not "which acronym"

The textbook split asks *what the decision is based on*. That question is genuinely ambiguous here,
because the answer is "both". A more load-bearing question is:

> **Where does the relationship graph live, and who traverses it?**

| | Zanzibar-style (SpiceDB, OpenFGA) | This library |
|---|---|---|
| Graph lives in | the authorization system, as **relationship tuples** | the **application's own database** — membership rows, ltree paths, reporting edges |
| Who traverses it | the authorization system | the application and its services, **before** the policy is called |
| Reaches the policy as | a stored graph the engine walks | resolved facts in `input` — the effective role, the ancestor chain, the derived id set |
| Consistency of the graph | the engine's problem — ZedTokens, snapshot reads | the database's problem — it is one transaction with the data |
| Reverse index ("who can see X") | a first-class capability | **absent — this library does not answer that question** |

So relations arrive **as input** and are reasoned over **as attributes**. That is not a lesser form of
ReBAC; it is a different placement of the same graph, with a different set of things that can go wrong.

## The list problem, which is where the difference bites

Single-resource `Check` is nearly identical across every design. The interesting divergence is
**filtering a list**: *"return page 3 of the catalogs this user may read, sorted, with a correct total."*

### How a Zanzibar engine answers it

Both major open-source engines answer with **a set of ids** that the application then pushes into its
own query, and both are explicit that this suits small result sets.

- **OpenFGA's `ListObjects`** returns the object ids of a type that a user has a relation with. Its
  *Search with Permissions* guide scopes that approach to roughly a thousand accessible objects, and
  says that beyond it you must paginate the whole list before you can search and sort — a partial list
  is not enough. It then recommends building a local index from the changes endpoint instead.
- **SpiceDB's `LookupResources`** is likewise described as the simplest starting point, appropriate
  while the accessible set is small; past that its own guidance moves to `CheckBulkPermissions` over
  database-fetched candidates (with cursor pagination, because offsets do not align with permission
  boundaries), and then to **Materialize**.

SpiceDB's *Protecting a List Endpoint* page states the architectural consequence plainly: authorization
and data retrieval are separate concerns, orchestrated in the application layer rather than inside a
database join.

### How this library answers it

OPA's **Compile API** returns a *residual* — the part of the policy that could not be decided without
the data — and the residual is turned into a JPA `Specification` that is **composed into the same
query**:

```
combined = scope.and( tagResidual.or(subtreeSpec) ).and( notDenied )
repo.findAll(combined, pageable)
```

One round trip, the database does the filtering, `COUNT` is derived from the same predicate so the total
is exact, and ordinary `Pageable` paging works because paging happens *after* filtering rather than
before it. The hierarchy is part of the predicate: the ltree resolver pushes `path <@ '<root>'` entirely
into SQL and **never materializes the descendant id set**.

**Where that claim stops being true, stated plainly:** the alternative recursive-CTE resolver
materializes a depth-bounded `id IN (…)` — which is exactly the id-set shape described above. The
pushdown is a property of the ltree implementation, not of the approach as such. And the supervised
read path derives its id set by **walking the reporting graph per request**; [[adr/0029-supervised-read-scope|ADR 0029]]
defers a Leopard-style precomputed closure until list latency forces it. That is the same wall Zanzibar
built Leopard — and AuthZed built Materialize — to climb.

## What this library does not have

Stated without hedging, because the absences are the honest half of the comparison:

- **No tuple store.** There is no `(object, relation, subject)` store, no schema language for relations,
  no `WriteRelationships`. The graph is application tables.
- **No consistency vocabulary.** SpiceDB offers `minimize_latency`, `at_least_as_fresh`,
  `at_exact_snapshot` and `fully_consistent`, with **ZedTokens** as its implementation of Zanzibar's
  *zookie*, guarding the New Enemy Problem. This library has none of that, and does not need it for the
  same reason it has no reverse index: the graph is read inside the same database transaction as the
  data, so there is no second copy to be stale. Hand the model a distributed tuple store and every one
  of those concepts would immediately become necessary.
- **No reverse index, and no "who can see this resource" API.**
- **No cross-service graph.** Relationships resolve through HTTP seams with breakers and fail-closed
  degradation ([[HTTP-RESILIENCE]]) — an availability coupling a tuple store does not have.

### The differentiator, after checking whether it still holds

The naive form of the claim — *"they hand you ids, we push the predicate into SQL"* — **does not survive
contact with the current products**, and the note this page grew from required checking exactly that:

**[AuthZed Materialize](https://authzed.com/docs/authzed/concepts/authzed-materialize)** is explicitly
inspired by Zanzibar's Leopard index. It precomputes configured permissions and streams permission-change
events so you can keep your own copy **colocated alongside the data it protects in your application
database**, then sort, filter and paginate over many thousands of authorized objects natively in your own
database. That is the same destination — permissions usable inside your query — reached by a different
route. It is a commercial AuthZed Dedicated capability rather than open-source SpiceDB, and it buys that
scale with a denormalized copy you must stream, store and reason about the freshness of.

So the accurate statement of the difference is about **when the predicate is produced and what has to be
kept in sync**, not about who can filter in the database:

| | Materialized (Leopard / Materialize) | Partial evaluation (this library) |
|---|---|---|
| Predicate produced | ahead of time, continuously | per request, from the policy itself |
| Second copy of permissions | yes — a denormalized store to keep fresh | none |
| Staleness window | yes, inherent to the stream | none — one transaction with the data |
| Cost model | infrastructure + streaming, scales to very large sets | one Compile call per list request, memoized; scales with policy complexity |
| Policy changes take effect | after re-materialization | immediately, on the next request |

## When to pick a Zanzibar engine instead

Genuinely, and without qualification — pick SpiceDB or OpenFGA when:

- **You need reverse lookups.** "Who can access this document?" is a first-class query there and is not
  implemented here at all.
- **The graph is deep, user-defined, or shared-with-shared.** Nested usersets, groups of groups, and
  document-sharing shapes are what the tuple model is *for*. This library's relations are a fixed set the
  application owns.
- **The graph spans many services with no shared database.** The whole premise here is that relationships
  are readable next to the data.
- **You need very large authorized sets filtered at speed** and are prepared to run a materialized index.
- **You need auditable, uniform authorization across a large organization** with its own schema language
  and tooling.

Pick this library's approach when the relationships already live in your database, you want the
authorization predicate *in* the SQL rather than in front of it, you want no second source of truth for
permissions, and you want policy changes live on the next request.

## Prior art in the Spring/OPA space

Compiling an OPA residual into a query in Spring is **not novel**, and it would be wrong to imply
otherwise:

- **[Thunx](https://github.com/xenit-eu/thunx)** (`xenit-eu`) — a pluggable ABAC/PBAC middleware that
  converts OPA query sets into technology-neutral *thunk expressions* and then into **QueryDSL**
  predicates, in a gateway-decides-early / service-postpones architecture. Actively maintained
  (v0.16.1, July 2026).
- **[opa-data-filter-spring-boot-starter](https://github.com/jferrater/opa-data-filter-spring-boot-starter)**
  (`jferrater`) — OPA partial evaluation into Spring Data **JPA and MongoDB** filters. Dormant; no
  releases, last commit December 2023.

What is distinctive here is not the partial-evaluation trick itself but the **combination**: the residual
composed with a hierarchy pushdown and a deny-override inside one paged query, driven by a relationship
spine (membership as sole path, ancestor traversal, a derived supervisor scope) that is resolved
application-side and reasoned over in Rego.

## Where this thread goes next

**Phase 8 (ReBAC-in-Rego)** in [[POC-ROADMAP]] is the experimental version of this same argument: push the
team/membership/grant join *into* OPA `data` and express the userset traversal in Rego, so the two
placements of the graph can be compared inside one repo rather than across two ecosystems. This page is
the position; Phase 8 is the measurement.

## Related

- [[PARTIAL-EVALUATION-FILTERING]] — the residual → `Specification` machinery in detail
- [[TEAM-BASED-AUTHORIZATION]] — the membership relation that is the spine
- [[HIERARCHICAL-AUTHORIZATION]] — the ancestor walk and the ltree pushdown
- [[SUPERVISED-READ-AND-STEP-UP]] — the derived, non-membership relation
- [[TAG-BASED-AUTHORIZATION]] — the attributes that refine what a relation opened
- [[TWO-LAYER-AUTHORIZATION]] — where each decision is enforced

## Sources

- OpenFGA — [Relationship Queries](https://openfga.dev/docs/interacting/relationship-queries) ·
  [Search with Permissions](https://openfga.dev/docs/interacting/search-with-permissions)
- SpiceDB — [Protecting a List Endpoint](https://authzed.com/docs/spicedb/modeling/protecting-a-list-endpoint) ·
  [Consistency](https://authzed.com/docs/spicedb/concepts/consistency) ·
  [FAQ](https://authzed.com/docs/spicedb/getting-started/faq)
- AuthZed — [What is Materialize?](https://authzed.com/docs/authzed/concepts/authzed-materialize)
- [Zanzibar: Google's Consistent, Global Authorization System](https://research.google/pubs/pub48190/) (Leopard index, zookies)
