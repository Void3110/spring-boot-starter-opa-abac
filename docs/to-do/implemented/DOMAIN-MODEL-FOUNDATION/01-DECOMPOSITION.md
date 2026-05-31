---
tags:
  - status/planned
  - type/project
  - area/spring-data
  - area/abac
  - area/spring
---

# Domain-model foundation — decomposition

> The ordered work list. Each ticket is one focused commit. Design rationale is in [[00-DESIGN]];
> the cases this must satisfy are in [[10-QA-TEST-CASES]]. **This is the implementer's work list.**

All packages under `dev.dmitriikonovalov.opaabac.data` (library) or
`dev.dmitriikonovalov.example.catalog` (example). Clean-room: original names only.

---

## Ticket 1 — Library: base model + tags

**Goal:** the reusable base/secure entity classes and the tag value object, in `opa-abac-spring-data`.

**Deliverables**
- `…data.model.BaseModel<ID>` — interface: `ID getId();` `Integer getVersion();`
- `…data.model.Taggable` — interface: `ResourceTags getTags();` `void setTags(ResourceTags tags);`
- `…data.model.ResourceTags` — immutable value object over `Map<String,Object>`; preserves JSON
  types (string / array / map-of-lists); helpers `string`, `list`, `contains`, `with`, `asMap`,
  `isEmpty`, static `empty`/`fromMap`; Jackson `@JsonValue` + `@JsonCreator`; defensive copies.
- `…data.model.ResourceTagsConverter` — `@Converter`, `AttributeConverter<ResourceTags,String>`
  over a shared `ObjectMapper`; `null`/empty → `"{}"`.
- `…data.model.AbstractAuditableEntity` — `@MappedSuperclass`,
  `@EntityListeners(AuditingEntityListener.class)`, `implements BaseModel<UUID>`; fields per
  [[00-DESIGN]] (`id`, `createdBy`, `lastModifiedBy`, `createdAt`, `lastModifiedAt`, `version`);
  proxy-safe id-only `equals`/`hashCode`; protected ctors.
- `…data.model.AbstractSecuredEntity` — `@MappedSuperclass extends AbstractAuditableEntity
  implements Taggable, AbacDataObject`; the JSONB `tags` field (`@Convert` +
  `@JdbcTypeCode(SqlTypes.JSON)`); `abacResourceType()` abstract; `abacResourceId()` =
  `getId().toString()`; `abacAttributes()` = `tags.asMap()`.
- `opa-abac-spring-data/build.gradle.kts` — promote `spring-boot-starter-data-jpa` from
  `implementation` to `api` (consumers inherit JPA).
- Unit tests: `ResourceTags` + `ResourceTagsConverter` round-trip (string/array/map-of-lists
  survive serialize→deserialize; empty → `{}` → empty).

**Acceptance**
- `./gradlew :opa-abac-spring-data:test` green.
- `opa-abac-core` still has **no** Spring dependency (unchanged).
- A secured entity, given some tags, returns them from `abacAttributes()`.

**What NOT to touch**
- The example app, Liquibase, or any controller (that's tickets 3–4).
- `opa-abac-core`, `opa-abac-spring-security`, the starter module.
- No `@AutoTag` machinery; no typed-ID value object.

---

## Ticket 2 — Library: locking repository + CRUD service

**Goal:** the generic, safe-by-default persistence service + the pessimistic-lock repository
fragment.

**Deliverables**
- `…data.repository.LockableJpaRepository<MODEL,ID>` — `@NoRepositoryBean`;
  `@Lock(PESSIMISTIC_WRITE) @Query("SELECT e FROM #{#entityName} e WHERE e.id = :id")
  Optional<MODEL> findByIdForUpdate(@Param("id") ID id);`
- `…data.service.AbstractCrudService<MODEL extends BaseModel<ID>, ID>` — constructor takes
  `JpaRepository<MODEL,ID>`; methods `getById`, `findById`, `getAll`, `exists` (read-only tx);
  `save`, `saveAndFlush`, `remove` (write tx); `getByIdForUpdate(id)` (guarded
  `instanceof LockableJpaRepository`, clear error otherwise); **`mutate(id, Consumer<MODEL>)`**
  (lock + mutate + save). `getById`/`getByIdForUpdate` not-found → a clear domain exception
  (define a small `EntityNotFoundException` in `…data.service` or reuse a suitable runtime
  exception — implementer's call, keep it neutral).
- *(optional)* `mutateWithRetry(id, fn)` — unlocked read + optimistic-retry loop.
- Unit tests for the guard (calling `getByIdForUpdate` on a non-lockable repo throws the clear
  error) — can use a mock/stub repository (no DB needed for this ticket's unit tests; the real
  locking proof is the IT in ticket 4).

**Acceptance**
- `./gradlew :opa-abac-spring-data:test` green.
- `getByIdForUpdate` on a repo that is *not* a `LockableJpaRepository` throws the documented
  `UnsupportedOperationException`, not an NPE/ClassCast.

**What NOT to touch**
- The example app (ticket 4 wires the service in).
- Don't add the reflective `create()` factory or service-level `AuditorAware` — explicitly out of
  scope per [[00-DESIGN]].

---

## Ticket 3 — Example: schema + entity adoption

**Goal:** the catalog entities adopt the base; the schema gains the new columns; the app still
boots under `ddl-auto: validate`.

**Deliverables**
- `example-catalog-management-service/build.gradle.kts` — add
  `implementation(project(":opa-abac-spring-data"))`.
- `…/resources/db/changelog/changes/0002-add-base-entity-columns.yaml` (+ include in
  `db.changelog-master.yaml`): per `catalog`/`category`/`product` add `version` (NOT NULL DEFAULT
  0), `created_by`, `last_modified_by`, `last_modified_at`, `tags` (jsonb NOT NULL DEFAULT
  `'{}'::jsonb`); add `created_at` to `category`+`product` (timestamptz NOT NULL DEFAULT now());
  GIN index on each `tags`.
- `CatalogEntity`, `CategoryEntity`, `ProductEntity` extend `AbstractSecuredEntity`; remove the now
  inherited `id`/`createdAt` members + getters; implement `abacResourceType()` (`"catalog"` /
  `"category"` / `"product"`); keep client-`UUID`-id constructors. `ProductEntity.abacAttributes()`
  optionally merges `categoryId`.
- Repositories add `LockableJpaRepository<…,UUID>` (all three for consistency).
- App config: `@EnableJpaAuditing` + an `AuditorAware<UUID>` bean (fixed demo principal).
- `CatalogMapper` updated only as needed (e.g. `getCreatedAt()` now inherited).

**Acceptance**
- `./gradlew build` green; the existing `CatalogCrudIT` passes **unchanged**.
- A `@SpringBootTest` boot against Testcontainers Postgres with `ddl-auto: validate` succeeds (the
  schema-match proof).
- Inserting an entity populates `createdAt`/`createdBy`; updating bumps `version` and
  `lastModifiedAt`.

**What NOT to touch**
- `ProductController` write path / `ProductService` (ticket 4).
- DTOs/OpenAPI spec — `tags` is not exposed in the API yet (deferred).
- Don't change id generation (stays client-side `UUID.randomUUID()`).

---

## Ticket 4 — Example: ProductService + concurrency proof

**Goal:** demonstrate the safe-write story end to end with a test that proves serialization.

**Deliverables**
- `…/example/catalog/domain/ProductService.java` (or a `service` package) — `@Service extends
  AbstractCrudService<ProductEntity, UUID>`.
- Refactor `ProductController.updateProduct` to `productService.mutate(productId, p -> { …set
  fields… })` (keep the `requireCategory`/`requireProduct` scoping behavior).
- `ProductConcurrencyIT` (Testcontainers Postgres): two threads call `productService.mutate(id,
  slowFn)` concurrently → they serialize (second blocks on the row lock), **no
  `StaleObjectStateException` escapes**, `version` increments **twice**, both mutations are applied.
- *(optional, illustrative)* a contrasting test that does unlocked `getById` + `save` from two
  threads and **reproduces** the stale-version failure — to show *why* `mutate` is the default.

**Acceptance**
- `./gradlew build` green; `ProductConcurrencyIT` green and deterministic (use a latch/barrier, not
  sleeps, to force overlap).
- `CatalogCrudIT` still green.

**What NOT to touch**
- Catalog/Category controllers (they can stay repo-direct for now; converting them is deferred).
- The library classes (they're fixed by tickets 1–2; only consume them here).

---

## Ticket 5 — E2E suite + docs

**Goal:** an e2e Postman/Newman suite through the gateway, plus the pattern-guide docs kept honest,
plus roadmap + Mulch updates.

**Deliverables**
- `scripts/postman/` — flesh out the skeleton committed with this package: real request bodies and
  assertions for **1. Auth → 2. Catalog → 3. Category → 4. Product → 5. Cleanup** over
  `{{base_url}}/api/v1` (note: the API **is** versioned — `/api/v1/...`). Auth folder obtains a
  Keycloak token; protected calls send `Authorization: Bearer {{access_token}}`. Honor the
  **in-network token caveat** (see [[E2E-TESTING]] / the `scripts/postman/README.md`): APISIX
  validates the issuer as `keycloak:8888`, so mint the token from inside the compose network (or run
  newman from a container on `opa-abac-example_default`).
- Verify/append the pattern guides [[DOMAIN-MODEL]] and [[CONCURRENCY-AND-LOCKING]] against what was
  actually built; fix any reality deltas.
- `docs/to-do/planning/POC-ROADMAP/POC-ROADMAP.md` — mark the base-entity slice of Phase 3 done and
  link this folder.
- Mulch: record durable insights (`ml record opa-abac …`) — e.g. base-stack module placement,
  `mutate` as the safe default, the JSONB mapping choice; `ml sync` (`.mulch/`-only commit).

**Acceptance**
- With the rig up (`ENABLE_OIDC=1 ./deploy.sh up --pods 2`), `cd scripts/postman && ./run-tests.sh`
  is green (all assertions pass).
- `run-tests.sh` is `bash -n`-clean; the collection + env JSON are valid JSON.
- Roadmap + Mulch updated; `ml doctor` clean.

**What NOT to touch**
- Don't push or open a PR. Don't add a CI newman job in this slice (the rig isn't in CI yet —
  note it as a follow-up).

---

## Cross-cutting acceptance (the whole slice)

- `./gradlew build` green (libraries + example + OpenAPI codegen + ITs).
- `CatalogCrudIT` unchanged-green; `ProductConcurrencyIT` green; `ddl-auto: validate` boots clean.
- `opa-abac-core` still Spring-free.
- E2E Postman suite green against the running rig.
- Docs (this folder + the two pattern guides) reflect what shipped; roadmap + Mulch updated.
- **Clean-room scan** clean: no proprietary names/paths/ids anywhere in the diff.

## Related

- Design: [[00-DESIGN]] · Run it: [[AUTONOMOUS-IMPLEMENTATION-PROMPT]] · Cases: [[10-QA-TEST-CASES]]
- Guides: [[DOMAIN-MODEL]], [[CONCURRENCY-AND-LOCKING]], [[E2E-TESTING]]
