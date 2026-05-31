---
tags:
  - status/done
  - type/project
  - area/spring-data
  - area/catalog-service
---

# STATUS — Ticket 4: ProductService + concurrency proof

> Filled in at the ticket-4 checkpoint. See [[01-DECOMPOSITION]] ticket 4.

**Status:** ☑ done

## What shipped
- `…/example/catalog/domain/ProductService.java` — `@Service extends AbstractCrudService<ProductEntity,
  UUID>`; constructor takes `ProductRepository` (which is lockable), so it inherits `mutate` /
  `getByIdForUpdate`.
- `ProductController.updateProduct` refactored to `productService.mutate(productId, entity -> { …set
  fields… })`. The `requireCategory`/`requireProduct` scoping (404 if the product isn't in that
  category/catalog) is kept **before** the locked mutate, so the lock covers only the read-modify-write.
- `ProductConcurrencyIT` (Testcontainers Postgres), two cases.

## Tests
`./gradlew build` — **green**. Example suite: `CatalogCrudIT` (3, **unchanged-green**),
`BaseEntityAuditingIT` (3), `ProductConcurrencyIT` (2):
- **I6 — two concurrent `mutate(id, fn)` serialize.** Latches force overlap (A signals once it holds
  the row lock; B waits, then contends on the locked row). Result: **no `StaleObjectStateException`
  escapes**, `version` advances **exactly twice** (0→2), and *both* mutations are present (A's name +
  B's sku) — proof they serialized against fresh state rather than clobbering. **Deterministic: ran
  3× back-to-back, green each time** (latch-forced, no `Thread.sleep`).
- **I7 (illustrative) — unlocked read+save reproduces the lost update.** Two threads read the same
  `version 0` snapshot, then both save; the second is rejected with
  `ObjectOptimisticLockingFailureException` — exactly the failure `mutate` prevents.

## Architecture review + refactor
Reviewed vs [[CONCURRENCY-AND-LOCKING]]. Conformance confirmed:
- `mutate` is the write path; `ProductService` **reuses** `AbstractCrudService` (no copy-paste).
- **No slow/external call inside the locked `fn`** — the controller's `fn` only sets fields from the
  already-parsed request (the guide's headline rule). The lock seam stays behind
  `LockableJpaRepository`/`AbstractCrudService` (DIP).
- Concurrency proven by a **latch-based IT on real Postgres** (semantics don't reproduce on H2), not
  by inspection, plus the contrasting failure case.

**No structural refactor needed** — not inventing churn. Two small hygiene fixes applied while writing
the IT (then re-run green): removed a dead `unwrap` helper + unused imports, and switched the
deprecated `Thread.getId()` → `threadId()`.

**Deliberately left (in scope per ticket "What NOT to touch"):** `ProductController` still injects the
repository for reads/create/delete and only routes the *locking* write through the service — a
hybrid, by design. Converting the other controllers/operations is deferred. The `requireProduct`
(unlocked) + `mutate` (locked) double-read is intentional: the scoping check must not hold a lock, and
keeping it outside `mutate` keeps the critical section minimal (per the guide's "keep slow work out of
the lock").

## Decisions recorded
Concurrency design behaves exactly as `mx-fe5c67` predicted (`mutate` as the safe default; pessimistic
serialization). Nothing non-obvious beyond it surfaced — no new Mulch record (skip per the rule).

## Commit
`feat(domain-model): add ProductService and prove mutate() serializes concurrent writers` — `b71c525`.
