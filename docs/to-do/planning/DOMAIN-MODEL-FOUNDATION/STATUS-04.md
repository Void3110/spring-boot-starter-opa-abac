---
tags:
  - status/planned
  - type/project
  - area/spring-data
  - area/catalog-service
---

# STATUS — Ticket 4: ProductService + concurrency proof

> Filled in at the ticket-4 checkpoint. See [[01-DECOMPOSITION]] ticket 4.

**Status:** ☐ not started

## What shipped
_`ProductService`; the `ProductController.updateProduct` refactor to `mutate(...)`;
`ProductConcurrencyIT` (+ optional contrast test)._

## Tests
_`ProductConcurrencyIT` result (serialized, no stale-version escape, version +2); how overlap was
forced (latch/barrier); `CatalogCrudIT` still green._

## Architecture review + refactor
_Vs [[CONCURRENCY-AND-LOCKING]]: no slow call in the locked tx; `mutate` is the write path.
What was refactored. If nothing: say so._

## Decisions recorded
_Any concurrency nuance found. Mulch record(s)._

## Commit
_`feat(domain-model): …` subject + short hash._
