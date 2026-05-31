---
tags:
  - status/done
  - type/project
  - area/spring-data
---

# STATUS — Ticket 2: Library locking repo + CRUD service

> Filled in at the ticket-2 checkpoint. See [[01-DECOMPOSITION]] ticket 2.

**Status:** ☑ done

## What shipped
- `…data.repository.LockableJpaRepository<MODEL,ID>` — `@NoRepositoryBean` fragment;
  `@Lock(PESSIMISTIC_WRITE) @Query("SELECT e FROM #{#entityName} e WHERE e.id = :id")
  Optional<MODEL> findByIdForUpdate(@Param("id") ID id)`. SpEL `#{#entityName}` → one query serves
  every entity.
- `…data.service.AbstractCrudService<MODEL extends BaseModel<ID>, ID>` — ctor takes
  `JpaRepository<MODEL,ID>`; reads (`@Transactional(readOnly=true)`): `findById`, `getById`,
  `getAll`, `exists`; writes (`@Transactional`): `save`, `saveAndFlush`, `remove`;
  `getByIdForUpdate(id)` (guarded `instanceof LockableJpaRepository`, else a clear
  `UnsupportedOperationException`); **`mutate(id, Consumer<MODEL>)`** = lock + apply + save in one tx.
  A `protected repository()` accessor lets subclasses add entity-specific finders.
- `…data.service.EntityNotFoundException` — small unchecked domain exception for missing-id reads
  (`getById`/`getByIdForUpdate`/`mutate`), so callers map one type to HTTP 404.
- **Decision — `mutateWithRetry` NOT included.** It was *optional* in the decomposition; pessimistic
  `mutate` is the headline safe default and adding an optimistic-retry variant now would be
  unexercised surface. Left as a documented future option in [[CONCURRENCY-AND-LOCKING]].

## Tests
`./gradlew :opa-abac-spring-data:test` — **green, 21 tests** (16 from ticket 1 + 5 new), no DB.
`AbstractCrudServiceTest` (Mockito, 5):
- U8 — `getByIdForUpdate` on a non-lockable repo → `UnsupportedOperationException` mentioning
  `LockableJpaRepository` (not NPE/ClassCast).
- U8 (mutate path) — `mutate` on a non-lockable repo throws **before** any `save` (verified
  `never().save`).
- I8 (unit) — `getByIdForUpdate` on a missing id → `EntityNotFoundException` naming the entity + id.
- `mutate` happy path — uses the **locked** `findByIdForUpdate` (not unlocked `findById`), applies
  `fn`, saves once.
- `getById` missing → `EntityNotFoundException`.

The real pessimistic-lock **serialization** proof is deferred to ticket 4's Testcontainers
`ProductConcurrencyIT` (locking semantics don't reproduce without real Postgres).

## Architecture review + refactor
Reviewed vs [[CONCURRENCY-AND-LOCKING]]. Conformance confirmed: both read styles exposed and named
for intent; `mutate` is the atomic lock+mutate+save; the `instanceof` guard fails loudly with a
message naming the missing interface and the offending repo class; `mutate`'s Javadoc warns to keep
slow/external work out of the locked tx (enforced in ticket 4's controller).

One real refactor, surfaced by the review (then re-tested green): the not-found message originally
used `getClass()`, i.e. the **service** name ("ProductService not found: …") — misleading. Added
`resolveModelName(...)` which reads the `MODEL` type argument off the concrete subclass's generic
superclass ("ProductEntity not found: …"), with a safe `"Entity"` fallback if an intermediate generic
layer hides it. Dropped the now-unused `EntityNotFoundException.of(Class, id)` factory rather than
leave dead surface (DRY/no-churn). `mutate` reuses `getByIdForUpdate` (no duplicated lock/not-found
logic); the lock seam stays behind one private `lockable()` (DIP).

## Decisions recorded
- Exception: a library-local unchecked `EntityNotFoundException` (not JPA's), kept neutral.
- `mutateWithRetry` deferred (above).
Mulch: nothing non-obvious beyond `mx-fe5c67`; no new record (skip per the "non-obvious only" rule).

## Commit
`feat(domain-model): add LockableJpaRepository and AbstractCrudService with mutate()` — hash at commit.
