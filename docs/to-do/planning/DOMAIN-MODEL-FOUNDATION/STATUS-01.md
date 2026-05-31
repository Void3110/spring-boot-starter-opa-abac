---
tags:
  - status/done
  - type/project
  - area/spring-data
  - area/abac
---

# STATUS — Ticket 1: Library base model + tags

> Filled in at the ticket-1 checkpoint. See [[01-DECOMPOSITION]] ticket 1.

**Status:** ☑ done

## What shipped
All in `opa-abac-spring-data`, package `dev.dmitriikonovalov.opaabac.data.model`:

- `BaseModel.java` — `getId()` / `getVersion()` contract the CRUD service is generic over.
- `Taggable.java` — `getTags()` / `setTags()`; implemented only by the secure base.
- `ResourceTags.java` — immutable, JSON-type-preserving value object over `Map<String,Object>`.
  Helpers `empty`/`fromMap`/`asMap`/`isEmpty`/`string`/`list`/`contains`/`with`; `@JsonValue` +
  `@JsonCreator`; deep defensive copies in (`with`/`fromMap`) and out (`asMap`).
- `ResourceTagsConverter.java` — `@Converter AttributeConverter<ResourceTags,String>` over a shared
  `ObjectMapper`; `null`/empty → `"{}"`, blank/`null` column → empty tags.
- `AbstractAuditableEntity.java` — `@MappedSuperclass`, `@EntityListeners(AuditingEntityListener)`,
  `implements BaseModel<UUID>`; fields `id`/`createdBy`/`lastModifiedBy`/`createdAt`/`lastModifiedAt`
  (`OffsetDateTime` + `@TimeZoneStorage(NATIVE)`)/`version` (`@Version`); proxy-safe id-only
  `equals`/`hashCode`; protected `()` + `(UUID)` ctors.
- `AbstractSecuredEntity.java` — `@MappedSuperclass extends AbstractAuditableEntity implements
  Taggable, AbacDataObject`; JSONB `tags` (`@Convert` + `@JdbcTypeCode(SqlTypes.JSON)`,
  `columnDefinition="jsonb"`, `NOT NULL`, default `ResourceTags.empty()`); `abacResourceType()`
  abstract; `abacResourceId()` = `id.toString()`; `abacAttributes()` = `tags.asMap()`.

Build change: `opa-abac-spring-data/build.gradle.kts` — promoted `spring-boot-starter-data-jpa`
from `implementation` to **`api`** (consumers inherit JPA; Jackson comes transitively). Dropped the
unused `h2` test dep; added `mockito-core` (for ticket 2's guard test). `opa-abac-core` untouched —
still Spring-free (jackson + slf4j only).

## Tests
`./gradlew :opa-abac-spring-data:test` — **green, 16 tests**, no DB (pure unit; the schema-match
proof is ticket 3's IT). Coverage:
- `ResourceTagsTest` (6) — U1 immutability of `with`, U5 `contains` on array+scalar, type-safe
  `string`/`list`, `asMap` immutable defensive copy, input-collection isolation, value equality.
- `ResourceTagsConverterTest` (5) — U2 array→`List` survives round-trip (not stringified), U3
  map-of-lists structure preserved, U4 empty/null → `"{}"` and null/blank/`{}` → empty, string tag.
- `AbstractSecuredEntityTest` (5) — U6 `abacAttributes()` == tags map, U7 type/id, null-setter
  normalization, U9 id-only `equals`/`hashCode` (ignores mutable tag state), unsaved-by-reference.

## Architecture review + refactor
Reviewed against [[DOMAIN-MODEL]]. Conformance confirmed: tags on the secure base only; secured
entity implements `AbacDataObject` cleanly; `core` Spring-free; JSONB via converter +
`@JdbcTypeCode`; `OffsetDateTime`/`timestamptz`; plain `UUID` ids.

Two small refactors applied (then re-tested green), both surfaced by the review + IDE diagnostics —
not invented churn:
1. `hashCode()` simplified from `Objects.hashCode(Hibernate.getClass(this).hashCode())` (a redundant
   double-hash) to `Hibernate.getClass(this).hashCode()`; removed the now-unused `Objects` import.
2. Removed a redundant `@SuppressWarnings("unchecked")` on `ResourceTags.deepCopy`.

No structural change needed — the value object + converter + two bases are already the cohesive,
single-responsibility seams the design calls for.

## Decisions recorded
No new open question. Design decisions were already captured pre-implementation in Mulch
`mx-fe5c67` (base-entity layer). No new record needed this ticket — nothing non-obvious beyond it
surfaced. (Mulch re-checked; skipped per "skip if nothing non-obvious".)

## Commit
`feat(domain-model): add library base/secure entities, tags, and JSONB converter` — hash filled at commit.
