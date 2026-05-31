---
tags:
  - status/planned
  - type/architecture
  - area/spring-data
  - area/abac
  - area/spring
---

# Domain-model foundation — design

> Part of [[POC-ROADMAP]] Phase 3. The decomposition into tickets is [[01-DECOMPOSITION]];
> this note is the *why* and *shape*.

## Problem

The catalog example's three entities (`CatalogEntity`, `CategoryEntity`, `ProductEntity`) are
plain `@Entity` classes with a client-supplied `UUID id` and nothing else shared: **no common
base, no optimistic `@Version`, no audit fields** (only `CatalogEntity` has a `createdAt`), **no
tags**, **no service layer** (controllers call Spring Data repositories directly), and **no
concurrency control** on updates.

Phase 3 layers real ABAC onto this app. You cannot do that cleanly on entities that aren't
*authorizable* and a write path that isn't *safe under concurrent writers*. This slice builds the
foundation: a base entity, a secure (taggable + authorizable) base entity, and a CRUD service with
explicit locking — all as a **reusable library**, demonstrated by the example.

This is a clean-room generalization of a layered base-entity stack the author built in a prior
production platform. We take the *ideas*, re-express them with original names, and make them
idiomatically Spring-native.

## The two base classes

A deliberate split, so apps pay only for what they use:

### `AbstractAuditableEntity` — the plain base (no tags)

`@MappedSuperclass`, `@EntityListeners(AuditingEntityListener.class)`, `implements BaseModel<UUID>`.

| Field | Mapping | Purpose |
|-------|---------|---------|
| `id` | `@Id @Column(updatable=false)` `UUID` | Client-supplied identity (see *IDs* below). |
| `createdBy` | `@CreatedBy @Column(name="created_by")` `UUID` | Who created it (Spring Data auditing). |
| `lastModifiedBy` | `@LastModifiedBy @Column(name="last_modified_by")` `UUID` | Who last touched it. |
| `createdAt` | `@CreatedDate @Column(name="created_at", updatable=false) @TimeZoneStorage(NATIVE)` `OffsetDateTime` | Creation timestamp. |
| `lastModifiedAt` | `@LastModifiedDate @Column(name="last_modified_at") @TimeZoneStorage(NATIVE)` `OffsetDateTime` | Update timestamp. |
| `version` | `@Version @Column(name="version")` `Integer` | Optimistic-lock counter. |

`equals`/`hashCode` are **id-only and proxy-safe** (compare via `Hibernate.getClass(...)` so a lazy
proxy equals its real entity). Protected no-arg constructor (JPA) + protected `(UUID id)`
constructor.

> **Timestamps:** `OffsetDateTime` with `@TimeZoneStorage(NATIVE)` and Liquibase `timestamptz`,
> matching the existing `CatalogEntity.createdAt` (the reference platform used `LocalDateTime`; we
> standardize on the offset-aware type already proven in this repo).

### `AbstractSecuredEntity` — the secure base (tags + authorizable)

`@MappedSuperclass extends AbstractAuditableEntity implements Taggable, AbacDataObject`.

This is where "secured" becomes **substantive**. In the source platform the secure concept was, in
places, just a marker interface; here a secured entity actually:

1. **Carries tags** — a JSONB `tags` column backed by the `ResourceTags` value object:
   ```java
   @Convert(converter = ResourceTagsConverter.class)
   @JdbcTypeCode(SqlTypes.JSON)
   @Column(name = "tags", columnDefinition = "jsonb", nullable = false)
   private ResourceTags tags = ResourceTags.empty();
   ```
2. **Is authorizable out of the box** — it implements the core `AbacDataObject`
   (`opa-abac-core`), so the framework can turn it into an `AbacContext.Resource` with no per-entity
   glue:
   - `abacResourceType()` — **abstract**; each entity declares its type (`"catalog"`, `"category"`,
     `"product"`).
   - `abacResourceId()` — default `getId().toString()`.
   - `abacAttributes()` — default `tags.asMap()`; overridable to merge intrinsic columns (e.g.
     `ProductEntity` can fold in `categoryId`).

So "extend `AbstractSecuredEntity` + declare your resource type" is the entire cost of making a
domain object policy-aware.

## `ResourceTags` + JSONB mapping

`ResourceTags` is an immutable value object wrapping `Map<String, Object>` that **preserves JSON
types** — string tags, **array tags** (membership, e.g. `members: ["u1","u2"]`), and map-of-lists.
Helpers: `string(key)`, `list(key)`, `contains(key, value)`, `with(key, value)` (returns a new
instance), `asMap()`, `isEmpty()`, `empty()`. Jackson `@JsonValue` serializes it to the map;
`@JsonCreator`/`fromMap` rebuilds it. Defensive copies in and out.

Preserving arrays natively (not stringified) is what later enables ABAC data-filtering queries such
as `jsonb_exists(tags -> 'members', :user)` — that is the payoff for choosing JSONB over a flat
string column.

**Mapping choice:** `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6.4 native JSON binding → real
`jsonb`) **plus** a `ResourceTagsConverter` (`AttributeConverter<ResourceTags, String>`) that
controls the exact JSON shape via a shared Jackson `ObjectMapper` (`null`/empty → `"{}"`). This is
the most idiomatic option on this stack and keeps the serialization unit-testable. A hand-rolled
Hibernate `UserType` is the contingency only — not needed here.

## Locking + the safe-by-default write path

The reference platform suffered a real **concurrent-write race**: one code path read a row
*unlocked*, did a slow external call, then saved with a now-stale `@Version`, throwing
`StaleObjectStateException` and silently wedging. Root cause: **inconsistent locking discipline** —
some paths locked, some didn't. (See [[CONCURRENCY-AND-LOCKING]] for the teachable write-up.)

The generalized fix is to make safe writes the *easy, obvious* path:

### `LockableJpaRepository<MODEL, ID>` (`@NoRepositoryBean`)

A fragment mixed into a concrete repository alongside `JpaRepository`:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT e FROM #{#entityName} e WHERE e.id = :id")
Optional<MODEL> findByIdForUpdate(@Param("id") ID id);
```
The SpEL `#{#entityName}` makes one method serve every entity (no per-entity boilerplate).
`PESSIMISTIC_WRITE` issues `SELECT … FOR UPDATE`, serializing contending writers on the row.

### `AbstractCrudService<MODEL extends BaseModel<ID>, ID>`

Constructor takes the `JpaRepository<MODEL, ID>`. It exposes **both** read styles explicitly so the
locking decision is always visible at the call site:

- reads (`@Transactional(readOnly=true)`): `getById`, `findById`, `getAll`, `exists`.
- writes (`@Transactional`): `save`, `saveAndFlush`, `remove`.
- `getByIdForUpdate(id)` — guarded by `instanceof LockableJpaRepository`; throws a clear
  `UnsupportedOperationException` if the repo doesn't extend it (opt-in, never silent).
- **`mutate(id, Consumer<MODEL> fn)`** — lock-for-update + apply `fn` + save, atomically. **The
  teachable safe default**: a one-liner that is correct under concurrency by construction.
- *(optional)* `mutateWithRetry(id, fn)` — unlocked read + mutate + save with a small
  optimistic-retry loop, for warm rows where blocking is too expensive.

We deliberately **drop** the reference service's reflective `create()` factory and its
`AuditorAware` plumbing inside the service — ids are controller-supplied and auditing is wired at
the application level, which is simpler and clearer.

## IDs — trimmed to plain `UUID`

The reference platform wrapped every id in a typed value object (`UUIDValue` → `OrderId`, …) because
*its* ABAC code was generic over the id type. **Our** ABAC layer is not: `AbacContext` and
`AbacDataObject` are String/`UUID`-based, and none of the planned features (hierarchical authz,
batch eval, partial-eval filtering) consume a typed id. So we use a **plain `UUID`** id in the base.

- **Tradeoff:** we give up compile-time "can't pass a `CategoryId` where a `ProductId` is expected"
  safety.
- **Why it's the right call now:** it's dramatically simpler and more teachable, removes `@EmbeddedId`
  mapping and DTO/mapper friction, and costs us nothing the current/planned ABAC design uses. If a
  future feature genuinely needs typed ids, reintroduce them then — it's an additive change.

## Example adoption

- The three catalog entities `extend AbstractSecuredEntity`, drop their now-inherited `id`/`createdAt`
  fields + getters, and implement `abacResourceType()`. **Ids stay client-supplied** via
  `UUID.randomUUID()` in the controllers (the base does not impose generation).
- `ProductEntity.abacAttributes()` may override to merge `categoryId`.
- App config gains `@EnableJpaAuditing` + an `AuditorAware<UUID>` bean returning a **fixed demo
  principal** for now (wired to the real authenticated principal in a later Phase-3 step).
- `ProductRepository extends JpaRepository<…>, LockableJpaRepository<…>` (recommended for all three
  for consistency, even if only Product uses it now).
- A new `ProductService extends AbstractCrudService<ProductEntity, UUID>`; `ProductController.updateProduct`
  is refactored to `productService.mutate(productId, p -> { … })`.

### Liquibase

New changeset `0002-add-base-entity-columns.yaml` (after `0001`). For **each** of `catalog`,
`category`, `product`:

- `version integer NOT NULL DEFAULT 0`
- `created_by uuid` (nullable), `last_modified_by uuid` (nullable)
- `last_modified_at timestamptz` (nullable)
- `tags jsonb NOT NULL DEFAULT '{}'::jsonb`

Plus `created_at`: keep on `catalog` (exists); **add** to `category` and `product` as
`timestamptz NOT NULL DEFAULT now()`. Add a GIN index on each `tags` column now (cheap; the future
partial-eval feature wants it).

> **`ddl-auto: validate` is the schema-match contract.** The app boots with `validate`, so the
> entity mappings must line up exactly with the migrated schema: `timestamptz` ↔
> `@TimeZoneStorage(NATIVE)`, `jsonb` ↔ the JSON jdbc type, `version` ↔ `int4`. A clean boot against
> a real Postgres *is* the proof the migration and the entities agree.

## Considered & rejected

| Option | Why rejected (for now) |
|--------|------------------------|
| **Typed-ID value objects** (`UUIDValue`-style) | No current/planned ABAC feature consumes them; pure ceremony + mapping friction here. Reintroducible later if needed. |
| **`@AutoTag` annotation + reflection listener** | Large machinery; not needed to prove the base stack. Tags are set explicitly now; auto-tagging is a later phase. |
| **Hand-rolled Hibernate `UserType` for JSONB** | `@JdbcTypeCode(SqlTypes.JSON)` + an `AttributeConverter` is more idiomatic on Hibernate 6.4 and easier to test. Keep `UserType` as a contingency only. |
| **Put the base classes in the example app** | They're meant to be a reusable, published part of the starter; example-only defeats that. They live in `opa-abac-spring-data`. |
| **Put them in `opa-abac-core`** | `core` must stay Spring-free; JPA/Spring annotations can't live there. |
| **`mutateWithRetry` as the default** | Pessimistic `mutate` is the safer default for hot aggregate rows; optimistic retry is the specialized tool, offered but not the headline. |

## Module placement

All new classes go in **`opa-abac-spring-data`** under `dev.dmitriikonovalov.opaabac.data`:
`…data.model` (entities, tags, converter, `BaseModel`, `Taggable`), `…data.repository`
(`LockableJpaRepository`), `…data.service` (`AbstractCrudService`). That module already has
`api(project(":opa-abac-core"))` and `spring-boot-starter-data-jpa`; we promote the latter to `api`
so consumers inherit JPA. `opa-abac-core` stays Spring-free.

## Deferred to later phases

`@AutoTag` processor · partial-eval → JPA `Specification` data filtering · batch evaluation ·
typed-ID value objects · starter auto-config of auditing/`AuditorAware` · an ArchUnit "writers must
lock" rule · exposing `tags` in API DTOs · the OPA client / `OpaAuthorizationManager` /
`@OpaPreAuthorize` / replacing the gateway enricher (each its own Phase-3 folder).

## Related

- Work breakdown: [[01-DECOMPOSITION]]
- Run it: [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]
- Pattern guides: [[DOMAIN-MODEL]], [[CONCURRENCY-AND-LOCKING]]
- Roadmap: [[POC-ROADMAP]]
