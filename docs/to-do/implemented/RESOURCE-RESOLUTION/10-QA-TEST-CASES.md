---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# 10 — QA test cases: Resource resolution (Phase 5.97)

> The concrete cases each [[01-DECOMPOSITION|ticket]]'s *Acceptance* references. **U** = unit (core /
> manager / cache / starter — no DB, no rig), **I** = integration (catalog service, real-Postgres
> Testcontainers), **P** = policy (`opa test`), **E** = e2e through the gateway (newman), **D** =
> doc-presence checks, **B** = the retro-audit **baseline** cells (must hold, explicitly NOT delivered
> by this slice). This is a **gate-semantics** slice: the cases assert *what the decision was made on*
> (resolved attributes, ancestors, the governing-root role), the two split failure semantics, the
> byte-identical opt-out, and the `409` version-binding contract.

## Conventions

- **Unit (manager):** the existing `OpaPreAuthorizeAuthorizationManagerTest` pattern — Mockito
  `OpaClient`/`RoleDefinitionSupplier`, reflective `MethodInvocation`, `ArgumentCaptor` on the
  `AbacContext`. The byte-identical cases serialize the OPA input with the production
  `ObjectMapper` config and compare strings.
- **Unit (cache):** drive `RequestContextHolder` directly (`MockHttpServletRequest` +
  `ServletRequestAttributes`); the no-context cases clear it.
- **Integration:** `ResourceResolutionGateIT` extends the `AbstractPostgresIT` pattern (**real
  Postgres via Testcontainers**, never H2) with a **programmable context-aware `OpaClient` stub**
  (decides from `input.resource.attributes` / `ancestors` — the proof the gate decided on *resolved*
  state). The existing CRUD ITs run with `opa.abac.resource-resolution.enabled=false` — they pin the
  **off-state baseline**.
- **e2e:** full rig — `./profile.sh up` → `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
  --pods 2`; `./deploy.sh build` to force new images; mint tokens **in-network** (issuer
  `keycloak:8888`); **restart OPA after the T5 rego edits**. Fixtures: the dedicated set under
  catalog `88888888-8888-8888-8888-888888888888` (registered; shared fixtures must not grow).
- **The pinned contract (ADR [[0013-attribute-rich-pre-authorization|0013]] + the two
  decomposition-pinned semantics):**

  | Semantic | Pinned |
  |---|---|
  | Instance resolution empty/throws | **DENY** — never an attribute-less context |
  | Ancestor chain throws / supplier absent | chain = `[]` — **direct-only**, never strips a direct grant |
  | Role lookup | **once, on the governing root** (`ancestors[0]`, else the leaf) |
  | Cache | write-through on allow (+ `resource()` path); **never read by decisions**; no web request → no-op |
  | No resolver bean / kill-switch off | gate context **byte-identical** to pre-5.97 |
  | Version drift (gate snapshot ≠ fresh load) | `409` problem+json `errorCode=STATE_CONFLICT` |
  | `OptimisticLockingFailureException` / `DataIntegrityViolationException` | `409 STATE_CONFLICT` (was `500`) |
  | Missing id behind an annotated `resourceId` | **`403`** (gate deny; was `404`) — wrong-scope-but-existing stays the handler's `404` |
  | Rego | mechanism needs zero changes; T5's conjunct only **narrows** the role-definition grant path |

---

## Unit — core types (T1)

| # | Case | Expected |
|---|------|----------|
| **U1** | `VersionGuard.requireUnchanged(snapshot v3, current v3)` | passes (no throw) |
| **U2** | snapshot v3, current v4 (and v4 vs v3) | `VersionConflictException`; message names type/id + expected/actual versions, nothing else |
| **U3** | a `null` version on **either** side | throws — guarding was requested, an unguardable resource fails loud, never silently passes |
| **U4** | the module boundary | the four new core types compile with **no Spring/JPA import**; `./gradlew build` green — `BaseModel extends Versioned` recompiles nothing else differently |

## Unit — the manager's resolution flow (T2)

| # | Case | Expected |
|---|------|----------|
| **U5** | **Byte-identical baseline:** support absent, an id'd check (`resourceType`+`resourceId`) | the serialized OPA input is **string-equal** to the pre-5.97 manager's for the same invocation (golden comparison) |
| **U6** | support present; resolver returns an instance with tags; chain returns `[root, mid]` | the captured `AbacContext.Resource` carries the instance's `abacAttributes()` **and** `ancestors == [root, mid]` (root-first); OPA called once |
| **U7** | governing-root role | with ancestors → `roleDefinitionSupplier.lookup(subject, root.type, root.id)` — called **once**, with the root; with an empty chain → looked up on the **leaf** |
| **U8** | resolver returns `Optional.empty()` | **deny**; `OpaClient` never invoked; nothing cached |
| **U9** | resolver **throws** | **deny**; `OpaClient` never invoked; nothing cached (instance failure ≠ ancestor failure) |
| **U10** | chain supplier **throws** (and: supplier `null`) | decision proceeds with `ancestors == []`, role on the leaf — the 5.5 collapse posture; never a partial chain |
| **U11** | cache write-through | OPA allow → `put(type, id, instance)` once; OPA deny → no `put`; the `resource()`-SpEL branch `put`s its instance on allow with its decision inputs unchanged |
| **U12** | the cache is never an input | pre-populate the cache with a *different* instance → the manager still calls the resolver and decides on the fresh instance. `RequestAttributesResourceCache`: put/get round-trip in a request context; `get` with a non-matching `Class` → empty; **no request context → `get` empty, `put` no-op, no throw** |
| **U13** | `AbstractProblemAdvice` + `VersionConflictException` | `409` `application/problem+json`, `errorCode=STATE_CONFLICT`; body free of stack/internal detail |

## Unit — starter composition (T3)

| # | Case | Expected |
|---|------|----------|
| **U14** | no `AbacResourceResolver` bean (defaults otherwise) | no `ResourceResolutionSupport` bean; the manager is wired exactly as pre-5.97 |
| **U15** | resolver bean registered | support wired; with an `AncestorResolver` bean present the bound `AncestorChainSupplier` delegates to it (invoke and compare chains); without one the supplier is absent → empty chain |
| **U16** | `opa.abac.resource-resolution.enabled=false` + resolver bean | **no** support wired — the kill-switch restores baseline with beans untouched |
| **U17** | `PersistenceConflictProblemAdvice` | present by default (dao on the test classpath) and maps **both** `OptimisticLockingFailureException` and `DataIntegrityViolationException` → `409 STATE_CONFLICT` problem+json; absent when the dao class is filtered from the classloader; a user-supplied bean overrides it |
| **U18** | properties | `opa.abac.resource-resolution.enabled` binds, defaults `true`; `spring-configuration-metadata.json` carries the property |

## Integration — catalog service, real Postgres (T4)

| # | Case | Expected |
|---|------|----------|
| **I1** | **Tag-match write allowed at the gate:** PUT a category whose tags satisfy the stub's attribute rule | `200`; the row updated — the gate decided on **resolved attributes** it could never see before |
| **I2** | **Tag-mismatch write denied at the gate:** same subject, the mismatched category | `403` problem+json `errorCode=ACCESS_DENIED`; **the handler never ran** (row byte-identical, no version bump) |
| **I3** | **`getCategory` parity:** the endpoint's first-ever annotation | `200`, body equals the resolved snapshot (the handler served the cache — assert the repository was not asked twice); an *existing* category under the **wrong catalog** in the path → **`404`** (the URL-scope rule stayed in the handler) |
| **I4** | **The deterministic version-guard race:** gate resolves v*n* → an out-of-band writer bumps the row (a test aspect ordered after the method-security interceptor, or an equivalent deterministic interleaving) → the handler's fresh load + guard | `409` problem+json `errorCode=STATE_CONFLICT`; the mutation did **not** apply (row carries the out-of-band writer's state) |
| **I5** | **The dao advice, live:** force a persistence conflict (a stale `@Version` save or an FK violation) | `409 STATE_CONFLICT` problem+json — **not `500`** (retro-audit fold-in #1's non-happy path, reached) |
| **I6** | **Missing id, both states:** an annotated endpoint given a nonexistent id — resolution **on**, then the CRUD-IT profile (resolution **off**) | on → **`403`** (pinned semantic #1); off → the pre-5.97 **`404`** — the kill-switch off-state proof, same assertion suite |
| **I7** | **Ancestors at the gate:** a nested category (and a product) under the fixture tree | the stub captured `input.resource.ancestors` root-first and the role was resolved on the **governing root** — an inherited grant now passes the *gate* (pre-5.97 it needed layer 3) |

## Policy — `opa test` (T5)

| # | Case | Expected |
|---|------|----------|
| **P1** | product write, role-def grant, `required_tags {region:[emea]}` ANY_OF | resource tagged `emea` → allow; tagged `apac` → deny — mirrors `category_test.rego`'s cells |
| **P2** | ALL_OF with two required keys | both satisfied → allow; one missing → deny |
| **P3** | a tag-requiring role against a resource input with **no attributes** | **deny** (fail-closed on missing attributes — the conjunct must not be vacuously true on absence) |
| **P4** | a role with **no** `required_tags` | behavior byte-identical to pre-T5 — **every pre-existing `product_test.rego`/`catalog_test.rego` case green unmodified** (vacuous back-compat) |
| **P5** | no role definition at all | the realm fallback decides exactly as before (the conjunct sits only on the role-definition grant path); `catalog.rego` mirrors the same five cells |

## e2e — through the gateway (T6)

| # | Case (live, through APISIX; fixture catalog `8888…`) | Expected |
|---|------|----------|
| **E1** | **The headline flip (story C4):** member, tag-gated write role, `viewer` realm role, PUT the `emea` category | **`200`** — pre-5.97 this was `403` (the leaf lookup found no role; the tag-blind fallback denied the viewer) |
| **E2** | **The fallback hole closes (C4's second half):** same member, `editor` realm role, PUT the `apac` category | **`403`** — pre-5.97 this was `200` (the realm fallback leaked a write the team role's tags deny) |
| **E3** | **The intended narrowing:** member, read-only team role, `editor` realm role, PUT | **`403`** — a role definition is present at the root, so the fallback no longer decides |
| **E4** | **Non-member unchanged:** no membership, `editor` realm role, PUT | the fallback still decides (**`200`**) — the byte-identical cell |
| **E5** | **Hierarchy parity:** root-granted member, GET a nested category | **`200` via the gate** — the inherited grant survived the move from layer 3 |
| **E6** | **The product sibling:** the tag-gated role, PUT the `apac` product | **`403`** — T5's conjunct live end-to-end |
| **E7** | **Suite-wide coexistence:** every existing `run-*.sh` matrix + `catalog-e2e` | green — user-mgmt untouched is *proven*; row counts and decisions numerically unchanged, except cells that pinned `404` for a **missing** id through an **annotated** endpoint, which flip to `403` (each one listed in STATUS) |

## Docs (T7)

| # | Case | Expected |
|---|------|----------|
| **D1** | the new guide `docs/guides/ATTRIBUTE-RICH-PRE-AUTHORIZATION.md` | exists, valid frontmatter; carries all four caveats: kill-switch-restores-baseline, unversioned-resources-undetected-window, **the supplier-outage scope-out (fold-in #2)**, the missing-id `403` posture |
| **D2** | reconciliations | [[TAG-BASED-AUTHORIZATION]] / [[HIERARCHICAL-AUTHORIZATION]] / [[ABAC-AUTHORIZATION]] reflect the redrawn layer-2/3 boundary; the `OpaPreAuthorize` Javadoc no longer says "a later phase"; `infra/README.md` + [[E2E-TESTING]] list the new matrix; ADR 0006/0013 bodies untouched |
| **D3** | the record | [[USER-STORIES]] C4 ✅; [[POC-ROADMAP]] 5.97 shipped; index table ticked T1–T7; folder moved to `implemented/` with the Shipped banner |

## Retro-audit baseline (hold, but NOT delivered by this slice)

| # | Cell | What holds |
|---|------|----------|
| **B1** | **Coexistence:** the user-mgmt IT suite + team matrix, byte-unmodified | green throughout — the slice never touches user-mgmt (the opt-in proof) |
| **B2** | **Supplier outage ≠ no-role (fold-in #2):** `HttpRoleDefinitionSupplier` behavior | byte-identical in this slice; the widening interplay (outage → empty → realm fallback decides) is **documented in the guide as scoped out + tracked**, never silently changed and never claimed fixed |
| **B3** | **Decide-under-protection TOCTOU (fold-in #4):** the user-mgmt subset/ceiling checks | existing pinning ITs stay green; the unlocked-actor-state window remains a **tracked follow-up** — this slice ships the *model* for the fix (version binding), not the user-mgmt fix itself |

## Fail-closed checklist (must all hold — nothing widens)

- [ ] **Instance failure is a deny, not a downgrade.** Resolver empty/throws → deny with no OPA call
      (U8/U9); there is no code path that builds an attribute-less context when resolution is active.
- [ ] **Ancestor failure only narrows.** A thrown chain → `[]` → direct-only; the direct leaf grant
      survives; no partial chain ever reaches the context (U10).
- [ ] **The two semantics are never confused.** Nothing maps an ancestor failure to deny, nothing maps
      an instance failure to collapse (U8–U10 assert both directions).
- [ ] **The opt-out is byte-identical.** No bean / kill-switch off → string-equal OPA input (U5),
      baseline wiring (U14/U16), pre-5.97 status codes (I6-off, the CRUD ITs).
- [ ] **The cache can't widen.** Never read by a decision (U12); populated only on allow (U11);
      request-bounded; no web request → no-op, the decision still resolves fresh.
- [ ] **The race is detected, not accepted.** Gate-snapshot drift → `409` and the mutation does not
      apply (I4); persistence conflicts answer `409`, never `500` (U17/I5); the guard throws on a
      `null` version rather than passing (U3).
- [ ] **The conjunct only narrows.** Tag-free roles byte-identical (P4); the fallback path untouched
      (P5); missing attributes deny (P3).
- [ ] **The suite proves the boundary.** Every pre-5.97 matrix green (E7); user-mgmt unmodified (B1);
      `AbacQueryService`/pagination/list paths out of the diff (grep `git diff --name-only`).

## Related

- [[01-DECOMPOSITION]] (the tickets these cases gate) · [[00-DESIGN]] (§3 behavior matrix, §6 proof
  obligations) · ADR [[0013-attribute-rich-pre-authorization|0013]] (the pinned forks) ·
  [[RETRO-AUDIT-2026-06-12]] (fold-ins #1–#4).
- The shipped templates: `docs/to-do/implemented/HIERARCHY-SINGLE-RESOURCE/10-QA-TEST-CASES.md` (the
  SPI + fail-closed shape) · `docs/to-do/implemented/PAGINATION-ENVELOPE/10-QA-TEST-CASES.md` (the
  contract-table + checklist shape).
