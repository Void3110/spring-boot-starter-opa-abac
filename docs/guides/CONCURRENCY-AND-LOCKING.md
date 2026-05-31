---
tags:
  - status/active
  - type/guide
  - area/spring-data
---

# Concurrency and locking — the safe-by-default write path

> How the library keeps concurrent writers from corrupting each other, and why `mutate(id, fn)` is
> the write path you should reach for first. The entities these operate on are described in
> [[DOMAIN-MODEL]]; the first implementation is planned in [[DOMAIN-MODEL-FOUNDATION]].

## The failure this prevents

A classic lost-update race, generalized from a real production incident:

1. Request A reads a row **without a lock** (`getById`), holding `version = 5`.
2. A does something slow (an external HTTP call, validation, a long compute).
3. Meanwhile request B locks the row, updates it, commits — the row is now `version = 6`.
4. A finally saves with its stale `version = 5` → Hibernate throws `StaleObjectStateException`.

If that exception is swallowed, the write silently vanishes and the workflow wedges. The root cause
is rarely "we forgot `@Version`" — it's **inconsistent locking discipline**: some code paths lock
the row before mutating, others read it unlocked and hope. The fix is to make the *safe* path the
*easy, obvious* one.

## Two reads, named for intent

`AbstractCrudService` exposes both, so the locking decision is always visible at the call site:

| Method | Lock | Use it when |
|--------|------|-------------|
| `getById(id)` | none (read-only tx) | You are **reading** — rendering, listing, a query. Never followed by a `save`. |
| `getByIdForUpdate(id)` | `PESSIMISTIC_WRITE` (`SELECT … FOR UPDATE`) | You are about to **mutate** a row that other writers may touch concurrently. |

`getByIdForUpdate` is backed by a tiny repository fragment:

```java
@NoRepositoryBean
public interface LockableJpaRepository<MODEL, ID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM #{#entityName} e WHERE e.id = :id")
    Optional<MODEL> findByIdForUpdate(@Param("id") ID id);
}
```

The SpEL `#{#entityName}` makes one query serve **every** entity, so there's no per-entity
boilerplate. A concrete repository mixes it in alongside `JpaRepository`:

```java
public interface ProductRepository
        extends JpaRepository<ProductEntity, UUID>,
                LockableJpaRepository<ProductEntity, UUID> { }
```

`getByIdForUpdate` is **opt-in and loud**: `AbstractCrudService` checks
`instanceof LockableJpaRepository` and throws a clear `UnsupportedOperationException` if the
repository didn't mix the fragment in — never a silent unlocked read.

## `mutate(id, fn)` — the default you should reach for

The atomic lock-mutate-save, in one transaction:

```java
@Transactional
public MODEL mutate(ID id, Consumer<MODEL> fn) {
    MODEL entity = getByIdForUpdate(id);   // SELECT ... FOR UPDATE
    fn.accept(entity);                      // apply the change
    return repository.save(entity);         // same tx; lock held until commit
}
```

Usage reads cleanly and is correct under concurrency by construction:

```java
productService.mutate(productId, p -> {
    p.setName(request.getName());
    p.setPriceCents(request.getPriceCents());
});
```

Two concurrent `mutate` calls on the same row **serialize**: the second blocks on the row lock until
the first commits, then proceeds against fresh state. No stale-version exception escapes; `version`
advances once per writer.

> **Keep slow work out of the locked transaction.** The lock is held for the lifetime of the
> transaction. Do external HTTP calls / heavy compute *before* `mutate` (or pass already-computed
> values into `fn`), so the critical section is just the read-modify-write. A `mutate` body that
> makes a network call holds the row lock for the whole round-trip — that's how you turn a
> correctness tool into a throughput problem.

## Pessimistic vs. optimistic — which to default to

- **Pessimistic (`mutate` / `getByIdForUpdate`)** — *blocks* contenders. Best for **hot aggregate
  rows** written by several paths concurrently, where you want the next writer to see committed state,
  not retry. This is the **default** for mutations here.
- **Optimistic (`@Version` + retry)** — *retries* on conflict. Best for **warm** rows where writes
  are rare and blocking is wasteful. Offered as an optional `mutateWithRetry(id, fn)` (unlocked read
  + mutate + save, retrying on the version conflict) — reach for it deliberately, not by default.

A plain `save()` after an unlocked read is the path that caused the incident above. Prefer `mutate`;
use `mutateWithRetry` when you've decided optimistic is the right trade; use a bare `save` only for
brand-new entities or single-writer paths.

## Verifying it actually serializes

The proof is an integration test (`ProductConcurrencyIT`) against **real Postgres** (locking
semantics don't reproduce on H2): two threads call `mutate(id, slowFn)` with a latch forcing their
critical sections to overlap. Assertions: no `StaleObjectStateException` escapes, `version` increments
**twice**, and both mutations are reflected. A contrasting unlocked `getById`+`save` path can be used
to *reproduce* the stale-version failure, documenting why the locked path is the default. Force
overlap with a latch/barrier — never `Thread.sleep`, or the test is flaky.

## Checklist

- Reading only? `getById`. About to mutate a possibly-contended row? `mutate` / `getByIdForUpdate`.
- Repository for a lockable entity extends `LockableJpaRepository`.
- No slow/external call inside a `mutate` body.
- Concurrency proven by a latch-based IT on real Postgres, not asserted by inspection.

## Related

- Entities these operate on: [[DOMAIN-MODEL]]
- Implementation plan + the concurrency ticket: [[DOMAIN-MODEL-FOUNDATION]]
