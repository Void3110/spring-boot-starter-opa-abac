---
tags:
  - status/done
  - type/project
  - area/spring-data
  - area/catalog-service
---

# STATUS — Ticket 3: Example schema + entity adoption

> Filled in at the ticket-3 checkpoint. See [[01-DECOMPOSITION]] ticket 3.

**Status:** ☑ done

## What shipped
- **Build:** `example-catalog-management-service/build.gradle.kts` — added
  `implementation(project(":opa-abac-spring-data"))`.
- **Liquibase:** `db/changelog/changes/0002-add-base-entity-columns.yaml` (+ included in
  `db.changelog-master.yaml`). Per `catalog`/`category`/`product`: `version int NOT NULL DEFAULT 0`,
  `created_by uuid`, `last_modified_by uuid`, `last_modified_at timestamptz`,
  `tags jsonb NOT NULL DEFAULT '{}'::jsonb`, GIN index `idx_<table>_tags`. `created_at timestamptz
  NOT NULL DEFAULT now()` **added** to `category` + `product` (already on `catalog` from 0001).
- **Entities:** `CatalogEntity` / `CategoryEntity` / `ProductEntity` now `extend AbstractSecuredEntity`;
  dropped their own `id`/`createdAt` fields + getters (inherited); each implements `abacResourceType()`
  (`"catalog"`/`"category"`/`"product"`); ctors call `super(id)` (client `UUID` still supplied).
  `ProductEntity.abacAttributes()` merges `categoryId`.
- **Repositories:** all three add `LockableJpaRepository<…,UUID>` alongside `JpaRepository`.
- **Config:** `config/AuditingConfig` — `@EnableJpaAuditing` + `AuditorAware<UUID>` (fixed demo
  principal `…de70`) + a `DateTimeProvider` (see Decisions).
- **Controller:** `CatalogController.createCatalog` no longer passes `OffsetDateTime.now()`
  (`createdAt` is now auditing-managed); dropped the unused import. `CatalogEntity` ctor lost its
  `createdAt` param.

## Tests
`./gradlew build` — **green** (all modules + example + OpenAPI codegen + ITs). Example suite:
- `CatalogCrudIT` (3) — **unchanged, green** (I2): entity adoption didn't disturb the CRUD walk-through.
- `BaseEntityAuditingIT` (3, new, Testcontainers Postgres): I3 insert populates `createdAt`/`createdBy`
  (= demo principal)/`lastModifiedAt`, `version` = 0, `tags` = `{}`; I4 update bumps `version` → 1 and
  `lastModifiedAt` while `createdAt`/`createdBy` stay; I5 `members:["a","b"]` + `tier:gold` round-trip
  through the `jsonb` column.
- **I1 schema-match proof:** both suites boot under `ddl-auto: validate` against the migrated schema —
  a clean boot is the proof the mappings (`timestamptz` ↔ `@TimeZoneStorage(NATIVE)`, `jsonb` ↔ JSON
  jdbc type, `version` ↔ `int4`) agree with 0001+0002.

## Architecture review + refactor
Reviewed vs [[DOMAIN-MODEL]]. Conformance confirmed: all three entities take tags only via the secure
base (none add their own); each is a clean `AbacDataObject`; `core` untouched/Spring-free; JSONB +
`OffsetDateTime`/`timestamptz` + plain `UUID` all inherited correctly; `ddl-auto: validate` passes.
GIN indexes are invisible to Hibernate `validate` (it checks columns/types, not indexes) so they don't
risk the boot. **No structural refactor needed** — the adoption is a clean "extend + declare type."
Not inventing churn.

Two real bugs were caught by the IT validation (fixed, then green) — these are the gate doing its job,
not refactors:
1. **Auditing date-type mismatch** — see Decisions. (production-code fix)
2. **Test FK seeding** — the first cut of `BaseEntityAuditingIT` inserted a product with a random
   `category_id`, violating `fk_product_category`; fixed to persist a real catalog→category chain first.
   (test-only fix; the FK is correct.)

## Decisions recorded
- **`OffsetDateTime` auditing needs an explicit `DateTimeProvider`.** Spring Data's default auditing
  date source emits `LocalDateTime` and can't convert it to `OffsetDateTime`, throwing on save. Fixed
  with a `DateTimeProvider` bean returning `OffsetDateTime.now()`, wired via
  `@EnableJpaAuditing(dateTimeProviderRef=…)`. **Mulch:** recorded as a `failure` in `opa-abac`.
- `created_at` on category/product defaulted to `now()` at the DB level (so existing-row migrations
  are safe) while auditing sets it on new rows. GIN index on `tags` kept (cheap; future partial-eval).

## Commit
`feat(domain-model): adopt base/secure entities in the catalog example + 0002 schema` — `6ad767b`.
