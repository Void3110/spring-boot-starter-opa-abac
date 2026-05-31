---
tags:
  - status/planned
  - type/project
  - area/spring-data
---

# STATUS — Ticket 2: Library locking repo + CRUD service

> Filled in at the ticket-2 checkpoint. See [[01-DECOMPOSITION]] ticket 2.

**Status:** ☐ not started

## What shipped
_`LockableJpaRepository`, `AbstractCrudService` (methods incl. `getByIdForUpdate`/`mutate`), the
not-found exception choice._

## Tests
_Command run + result; the guard test (non-lockable repo → clear error)._

## Architecture review + refactor
_Vs [[CONCURRENCY-AND-LOCKING]]: both read styles exposed; `mutate` atomic; guard fails loudly.
What was refactored. If nothing: say so._

## Decisions recorded
_e.g. exception type; whether `mutateWithRetry` was included. Mulch record(s)._

## Commit
_`feat(domain-model): …` subject + short hash._
