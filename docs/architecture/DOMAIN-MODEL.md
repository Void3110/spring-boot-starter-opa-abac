---
tags:
  - status/active
  - type/architecture
  - area/spring-data
  - area/abac
---

# Domain model — base entities, tags, and authorizability

> How domain entities are structured so they get auditing, optimistic locking, and ABAC
> tags "for free," and become authorizable with almost no per-entity code. The base classes
> live in the `opa-abac-spring-data` library and are demonstrated by the catalog example.
>
> Decomposition + status for the first implementation (shipped): [[DOMAIN-MODEL-FOUNDATION]].
> The write-side concurrency story has its own guide: [[CONCURRENCY-AND-LOCKING]].

## Why a base layer at all

Authorization is only interesting when the things being authorized carry **attributes**. ABAC
decisions read subject attributes *and* resource attributes. So every secured domain object needs a
consistent place to hold attributes (tags), a stable identity, and the bookkeeping (who/when/version)
that makes concurrent edits and audit trails sane. Rather than repeat that on every entity, the
library provides two `@MappedSuperclass` bases — and the *secure* one is authorizable out of the box.

## The two bases

```
AbstractAuditableEntity   (id + audit + @Version)          — plain, no tags
        ▲
        │ extends
AbstractSecuredEntity      (+ JSONB tags, implements AbacResource)  — authorizable
        ▲
        │ extends
CatalogEntity / CategoryEntity / ProductEntity   (declare abacResourceType())
```

### `AbstractAuditableEntity` — the plain base

A `@MappedSuperclass` with `@EntityListeners(AuditingEntityListener.class)`, implementing
`BaseModel<UUID>`:

- `id` — a plain `UUID`, `@Id`, `updatable=false`. Client-supplied (the app generates it; the base
  does not impose a strategy).
- `createdBy` / `lastModifiedBy` — `UUID`, populated by Spring Data auditing (`@CreatedBy` /
  `@LastModifiedBy`) from an `AuditorAware<UUID>` bean.
- `createdAt` / `lastModifiedAt` — `OffsetDateTime`, `@CreatedDate` / `@LastModifiedDate`, mapped
  with `@TimeZoneStorage(NATIVE)` to Postgres `timestamptz`.
- `version` — `Integer`, `@Version`: optimistic-lock counter (see [[CONCURRENCY-AND-LOCKING]]).

`equals`/`hashCode` are **id-only and proxy-safe** — compare the real classes (via
`Hibernate.getClass`) so a lazy proxy equals its loaded entity, and never use mutable fields.

> **Why plain `UUID` (not a typed-id value object):** the ABAC layer (`AbacContext`,
> `AbacResource`) is String/`UUID`-based and no current or planned feature needs a typed id, so a
> wrapper would be ceremony with no payoff. It can be reintroduced additively if a future feature
> ever requires it.

### `AbstractSecuredEntity` — the authorizable base

Extends the plain base and adds two things:

1. **Tags** — a JSONB `tags` column backed by the `ResourceTags` value object:
   ```java
   @Convert(converter = ResourceTagsConverter.class)
   @JdbcTypeCode(SqlTypes.JSON)
   @Column(name = "tags", columnDefinition = "jsonb", nullable = false)
   private ResourceTags tags = ResourceTags.empty();
   ```
2. **`AbacResource`** (from `opa-abac-core`) — so the framework can build an
   `AbacContext.Resource` with no per-entity glue:
   - `abacResourceType()` — **abstract**; each entity declares its type.
   - `abacResourceId()` — default `getId().toString()`.
   - `abacAttributes()` — default `tags.asMap()`; override to merge intrinsic columns.

The whole cost of making a domain object policy-aware is *"extend `AbstractSecuredEntity` and declare
your resource type."* That is the deliberate fix for the "secure is just a marker" pattern: here,
secure means **carries attributes and is authorizable**.

```java
@Entity @Table(name = "product")
public class ProductEntity extends AbstractSecuredEntity {
    // ... product fields ...
    @Override public String abacResourceType() { return "product"; }
    @Override public Map<String, Object> abacAttributes() {
        // tags plus an intrinsic attribute the policy may care about
        var attrs = new HashMap<>(getTags().asMap());
        attrs.put("categoryId", categoryId.toString());
        return attrs;
    }
}
```

> **Tags belong on the *secure* base, not the plain one.** An entity that opts into
> `AbstractAuditableEntity` gets identity + audit + version without paying for a tag column it may
> never use. Only `AbstractSecuredEntity` carries `tags`. Keep it that way.

## `ResourceTags` — the tag value object

An immutable wrapper over `Map<String, Object>` that **preserves JSON types**:

- **string tags** — `"tier": "gold"`
- **array tags** — `"members": ["u1", "u2"]` (membership; the key enabler for data-filtering)
- **map-of-lists** — structured grouping

Helpers: `string(key)`, `list(key)`, `contains(key, value)`, `with(key, value)` (returns a new
instance — immutability), `asMap()`, `isEmpty()`, `empty()`, `fromMap(...)`. Jackson `@JsonValue`
serializes to the underlying map; `@JsonCreator`/`fromMap` rebuilds. Defensive copies in and out.

Why arrays must stay arrays (not stringified): it lets a future partial-evaluation feature push ABAC
predicates into SQL, e.g.

```sql
SELECT * FROM product WHERE jsonb_exists(tags -> 'members', :currentUser);
```

That data-filtering payoff is the reason `tags` is `jsonb`, not a flat text column.

### JSONB mapping

`@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6.4's native JSON binding → real `jsonb`) **plus** a
`ResourceTagsConverter` (`AttributeConverter<ResourceTags, String>`) that owns the exact JSON shape
via a shared Jackson `ObjectMapper` (`null`/empty → `"{}"`). This is the most idiomatic option on
this stack and keeps serialization unit-testable. A hand-rolled Hibernate `UserType` is a
contingency only — not used here.

## Auditing wiring

The app enables `@EnableJpaAuditing` and provides an `AuditorAware<UUID>` bean. For now it returns a
**fixed demo principal**; a later Phase-3 slice wires it to the real authenticated principal (so
`created_by`/`last_modified_by` reflect who actually acted). The base classes need no change for that
— only the bean does.

> **`OffsetDateTime` needs an explicit `DateTimeProvider`.** Spring Data's default auditing date
> source produces a `LocalDateTime`, which it then **cannot convert** to the `OffsetDateTime` the base
> timestamps use — it throws *"Cannot convert unsupported date type java.time.LocalDateTime to
> java.time.OffsetDateTime"* on the first save. Supply a `DateTimeProvider` bean that returns an
> `OffsetDateTime` and reference it via `@EnableJpaAuditing(dateTimeProviderRef = "…")`; auditing then
> uses it as-is. The example does exactly this in `AuditingConfig`.

## Schema contract (`ddl-auto: validate`)

The app boots with `spring.jpa.hibernate.ddl-auto: validate`, so **the entity mappings and the
Liquibase-managed schema must agree exactly**. The base layer adds (per secured table): `version int`,
`created_by uuid`, `last_modified_by uuid`, `created_at`/`last_modified_at timestamptz`, `tags jsonb`.
A clean boot against a real Postgres *is* the proof that the migration and the mappings line up
(`timestamptz` ↔ `@TimeZoneStorage(NATIVE)`, `jsonb` ↔ the JSON jdbc type, `version` ↔ `int4`).

## Where it lives

| Package | Contents |
|---------|----------|
| `dev.dmitriikonovalov.opaabac.data.model` | `BaseModel`, `Taggable`, `ResourceTags`, `ResourceTagsConverter`, `AbstractAuditableEntity`, `AbstractSecuredEntity` |
| `dev.dmitriikonovalov.opaabac.data.repository` | `LockableJpaRepository` (see [[CONCURRENCY-AND-LOCKING]]) |
| `dev.dmitriikonovalov.opaabac.data.service` | `AbstractCrudService` (see [[CONCURRENCY-AND-LOCKING]]) |

These are in `opa-abac-spring-data` (it already depends on `opa-abac-core` and Spring Data JPA).
`opa-abac-core` stays **Spring-free** — none of this leaks into it.

## Related

- Write-side concurrency + locking: [[CONCURRENCY-AND-LOCKING]]
- Implementation plan: [[DOMAIN-MODEL-FOUNDATION]]
- Roadmap: [[POC-ROADMAP]]
