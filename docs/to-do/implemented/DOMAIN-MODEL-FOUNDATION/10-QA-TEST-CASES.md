---
tags:
  - status/planned
  - type/project
  - area/spring-data
  - area/abac
  - area/build
---

# Domain-model foundation — QA test cases

> The concrete cases the unit + integration + e2e work in [[01-DECOMPOSITION]] must satisfy.
> Grouped by layer. Each row names a check; the implementer turns it into a test or a manual step.

## Unit (library, no DB) — tickets 1–2

| # | Case | Expected |
|---|------|----------|
| U1 | `ResourceTags.with("type","product")` then `asMap()` | `{"type":"product"}`; original instance unchanged (immutability). |
| U2 | Array tag round-trip through the converter | `members:["u1","u2"]` survives `convertToDatabaseColumn` → `convertToEntityAttribute` as a `List`, not a stringified value. |
| U3 | Map-of-lists tag round-trip | nested structure preserved. |
| U4 | Empty / null tags | `convertToDatabaseColumn(empty)` → `"{}"`; `convertToEntityAttribute(null/"")` → empty `ResourceTags`. |
| U5 | `contains(key,value)` on an array tag | true when present, false otherwise / when key missing. |
| U6 | Secured entity `abacAttributes()` | equals `tags.asMap()` (or the overridden merge for `ProductEntity`). |
| U7 | Secured entity `abacResourceType()` / `abacResourceId()` | returns the declared type / `id.toString()`. |
| U8 | `getByIdForUpdate` on a non-`LockableJpaRepository` | throws the documented `UnsupportedOperationException` (clear message), not NPE/ClassCast. |
| U9 | `equals`/`hashCode` | two instances with the same `id` are equal; id-only; tolerant of a Hibernate proxy vs. the real class. |

## Integration (Testcontainers Postgres) — tickets 3–4

| # | Case | Expected |
|---|------|----------|
| I1 | App boots with `ddl-auto: validate` against the `0001`+`0002` schema | clean boot — entity mappings match columns (`timestamptz`/`jsonb`/`int4`). **This is the schema-match proof.** |
| I2 | `CatalogCrudIT` (existing) | passes **unchanged** after entities adopt the base. |
| I3 | Insert a secured entity | `createdAt` + `createdBy` populated by auditing; `version` = 0; `tags` stored as `{}` jsonb. |
| I4 | Update a secured entity | `version` increments; `lastModifiedAt`/`lastModifiedBy` change; `createdAt`/`createdBy` unchanged. |
| I5 | Persist tags and read back | a secured entity with `members:["a","b"]` round-trips through the `jsonb` column. |
| I6 | **Concurrency: two `mutate(id, slowFn)`** (use a latch to force overlap) | writers **serialize**; no `StaleObjectStateException` escapes; `version` increments **twice**; both mutations applied. |
| I7 | *(illustrative)* two unlocked `getById`+`save` writers | reproduces the stale-version failure — documents *why* `mutate` is the safe default. |
| I8 | `getByIdForUpdate` for a missing id | clear not-found domain exception, not an empty `Optional` leaking out. |

## E2E (Postman/Newman, via the gateway) — ticket 5

Prereq: full rig up — `ENABLE_OIDC=1 ./deploy.sh up --pods 2`. Token minted **in-network** (issuer
`keycloak:8888`); see [[E2E-TESTING]] for the caveat. Base URL `http://localhost:9085`, API under
`/api/v1`.

| # | Case | Expected |
|---|------|----------|
| E1 | Auth: Keycloak password-grant (`demo`/`demo`, client `catalog-gateway`) | 200 + an `access_token`; captured into a collection variable. |
| E2 | Create catalog (`POST /api/v1/catalogs`) with `Authorization: Bearer …` | 201; `id` captured. |
| E3 | Create category under the catalog | 201; `catalogId` matches; `id` captured. |
| E4 | Create product under the category | 201; `categoryId` matches; `id` captured. |
| E5 | Get product | 200; fields match what was created. |
| E6 | Update product (PUT) | 200; changed fields reflected. |
| E7 | List products in the category | 200; the created product is present. |
| E8 | Cleanup: delete the catalog | 204; cascade removes category + product. |
| E9 | *(today's posture)* request **without** a token | reaches the gateway; with OIDC `unauth_action: auth` a browser flow redirects (302). Bearer-less API calls are an auth case to assert once real service-side ABAC lands — for now OPA is allow-all, so a valid token + allow-all = 2xx. |

> **Note on authz depth today:** OPA runs an allow-all placeholder and the service does no
> JWT/ABAC check yet, so E1–E8 prove the *plumbing* (identity reaches the app, CRUD works through
> the gateway), not fine-grained decisions. The viewer-vs-editor matrix (E10+) is a placeholder to
> fill when `@OpaPreAuthorize` + a real policy land in a later Phase-3 slice.

## Definition of done

All Unit + Integration cases green in `./gradlew build`; E2E green against the running rig; the
schema-match boot (I1) and the concurrency proof (I6) both pass; clean-room scan clean.

## Related

- [[01-DECOMPOSITION]] · [[00-DESIGN]] · [[CONCURRENCY-AND-LOCKING]] · [[E2E-TESTING]]
