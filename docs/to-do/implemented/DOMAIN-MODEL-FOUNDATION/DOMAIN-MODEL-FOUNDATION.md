---
tags:
  - status/done
  - type/index
  - area/spring-data
  - area/abac
  - area/spring
---

# Domain-model foundation

> **✅ Shipped (2026-05-31).** All 5 tickets implemented, tested, and committed on
> `feature/void3110/domain-model-foundation`. First load-bearing slice of [[POC-ROADMAP]]
> **Phase 3 (library spine)** — moved here to `implemented/` on ship.

This folder is the full work package for the **foundational domain-model layer**: the reusable
base entity stack that everything else in Phase 3 builds on. It is written to be **implemented
autonomously** — the design, the work breakdown, and a self-contained
[[AUTONOMOUS-IMPLEMENTATION-PROMPT]] are all here.

## What this slice delivers

A small, teachable set of reusable building blocks in the `opa-abac-spring-data` library, adopted
by the catalog example:

- **`AbstractAuditableEntity`** — a plain JPA base: `UUID` id, optimistic `@Version`, audit fields
  (created/last-modified by + at). No tags.
- **`AbstractSecuredEntity`** — extends the base, adds a JSONB **`tags`** value object, and
  implements the core **`AbacDataObject`** so a secured entity is *authorizable out of the box*
  (fixing the "secure is just a marker" gap from the source platform).
- **`ResourceTags`** + converter — a type-preserving JSONB tag container.
- **`LockableJpaRepository`** + **`AbstractCrudService`** — a generic CRUD service whose
  `getByIdForUpdate(id)` / `mutate(id, fn)` make "safe under concurrent writers" the default,
  teachable choice.
- **Example adoption** — the three catalog entities extend the secure base; a Liquibase migration
  adds the new columns; `ProductService` + a concurrency test prove the locking story; an e2e
  Postman/Newman suite exercises CRUD through the gateway.

## File glossary

| File | Role |
|------|------|
| [`DOMAIN-MODEL-FOUNDATION.md`](DOMAIN-MODEL-FOUNDATION.md) | This index. |
| [`00-DESIGN.md`](00-DESIGN.md) | The design: base/secure split, tags + JSONB, locking + `mutate`, the trims, considered-&-rejected. |
| [`01-DECOMPOSITION.md`](01-DECOMPOSITION.md) | The ordered work list — five tickets, each with Goal / Deliverables / Acceptance / What-NOT-to-touch. **The implementer's work list.** |
| [`AUTONOMOUS-IMPLEMENTATION-PROMPT.md`](AUTONOMOUS-IMPLEMENTATION-PROMPT.md) | Self-contained prompt to implement this package autonomously, ticket by ticket, with a review gate and checkpoints. |
| [`10-QA-TEST-CASES.md`](10-QA-TEST-CASES.md) | The cases the unit + integration + e2e work must satisfy. |
| `STATUS-01.md` … `STATUS-05.md` | One per ticket — filled in at each checkpoint during the run (what shipped, tests, review + refactor, e2e, decisions, commit). |

## Tickets (status)

| # | Ticket | Status | Status note |
|---|--------|--------|-------------|
| 1 | Library: base model + tags | ☑ done | `STATUS-01.md` |
| 2 | Library: locking repo + CRUD service | ☑ done | `STATUS-02.md` |
| 3 | Example: schema + entity adoption | ☑ done | `STATUS-03.md` |
| 4 | Example: `ProductService` + concurrency proof | ☑ done | `STATUS-04.md` |
| 5 | E2E suite + docs | ☑ done | `STATUS-05.md` |

## Critical path

```
1 (base model + tags)
  └─> 2 (locking repo + service)        [needs BaseModel from 1]
  └─> 3 (schema + entity adoption)      [needs the base classes from 1]
        └─> 4 (ProductService + concurrency)   [needs 2 + 3]
              └─> 5 (e2e + docs)                [needs the app from 3/4 running]
```

Tickets 1 → 2 are the standalone library foundation; if only a short window is available, landing
1 + 2 already delivers reusable value. 3 → 4 → 5 then layer it onto the example.

## Conventions

- **Clean-room IP boundary** — original neutral names only; never copy proprietary source, names,
  or docs. Root [`CLAUDE.md`](../../../../CLAUDE.md) → "IP Boundary".
- **Commit identity** — `Void3110 <void31102025@gmail.com>` (repo-local). One focused commit per
  ticket; `Co-Authored-By: Claude` trailer welcome.
- **No push** — local + the feature branch only; the maintainer pushes.
- **Docs conventions** — [`TAG-SYSTEM.md`](../../../TAG-SYSTEM.md): one `status/`, one `type/`,
  ≥1 `area/`; `UPPER-KEBAB-CASE.md` filenames; `[[wikilinks]]` within the vault.

## Related

- Roadmap: [[POC-ROADMAP]] (Phase 3)
- Pattern guides this work is checked against: [[DOMAIN-MODEL]], [[CONCURRENCY-AND-LOCKING]]
- Next example app that consumes these attributes: [[USER-MANAGEMENT-SERVICE]]
