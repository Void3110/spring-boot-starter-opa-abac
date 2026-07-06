---
tags:
  - status/planned
  - type/project
  - area/security
  - area/api
---

# USER-DIRECTORY-PORT — decomposition

> The ordered work list for [[USER-DIRECTORY-PORT|Slice 2]], decomposed from [[00-DESIGN]] +
> [[0020-user-directory-port|ADR 0020]] (the 8 pinned forks). **6 tickets, one focused commit each.**
> Each ticket's *Acceptance* references a case in [[10-QA-TEST-CASES]]. Packages: library under
> `dev.dmitriikonovalov.opaabac.security.directory`; the new module under
> `dev.dmitriikonovalov.opaabac.keycloak.directory`; the endpoint under
> `dev.dmitriikonovalov.example.usermgmt.web`. **`opa-abac-core` is not touched.**

## Critical path

```
T1 ──► T2 ──► T3 ──► T4 ──► T5 ──► T6
 (port)  (kc impl) (autoconf) (endpoint) (realm cfg) (e2e + SPA + docs)
```

**Backend-first (ADR 0020 §sizing).** T1 → T5 are all backend/config, **newman-provable without the
SPA**; T6 is the integration cap (SPA picker rewrite + e2e + docs + folder move). **T1 is independently
landable** (pure library SPI + `NoOp` + unit test — no module, no Keycloak). T2 depends on T1's port;
T3 wires T2 into the starter; T4 is the endpoint over the port (works against `NoOp` until T2/T3 land);
T5 is the realm/deploy config the live e2e needs; T6 proves the whole thing and flips the SPA. If the
window is short, **T1–T4 are the reusable library core** (the port + impl + wiring + endpoint) — the
realm/SPA work (T5/T6) can follow. The ★ review + checkpoint after each ticket is mandatory.

---

## T1 — Port SPI: `UserDirectory` + `DirectoryUser` + `NoOpUserDirectory` (library)

**Goal.** Ship the reusable identity-search seam — the interface, the type-bounded row record, and the
empty default — in `opa-abac-spring-security`. Independently landable (pure library + unit tests).

**Deliverables.**
- `UserDirectory` interface (`opa-abac-spring-security`, `…security.directory`):
  `List<DirectoryUser> search(String query, int limit)` — Javadoc pins the **fail-closed-to-empty,
  never-throw** contract and the `limit` semantics (ADR 0020 §1, §8).
- `DirectoryUser` record — `(String subject, String displayName)`, the **type-bounded disclosure**
  ceiling (no email/roles/attributes — the privacy control is the type, §7).
- `NoOpUserDirectory implements UserDirectory` — returns `List.of()` for every query (the lean-starter
  fallback and the `@ConditionalOnMissingBean` default T3 wires).
- Consumers named: the endpoint (T4) injects `UserDirectory`; the starter (T3) supplies either
  `NoOpUserDirectory` or (T2's) `KeycloakUserDirectory`. No consumer in this ticket beyond the unit test.

**Acceptance.** [[10-QA-TEST-CASES]] **U1** (`NoOpUserDirectory.search(anything)` → empty list; the
record exposes exactly `subject`+`displayName`). `./gradlew :opa-abac-spring-security:test`.

**What NOT to touch.** `opa-abac-core` (this is `spring-security`, mirroring `ResourceOwnershipResolver`
— not core). The record is the **disclosure ceiling** — do not add fields. No Keycloak dependency in this
module (the impl is T2's separate module). Build-breaker: none (additive new types only).

---

## T2 — New module `opa-abac-keycloak-directory`: `KeycloakUserDirectory` impl

**Goal.** The concrete, reusable Keycloak-admin implementation of the port — in its own optional module,
using a least-privilege `client_credentials` service account, fail-closed to empty.

**Deliverables.**
- **New Gradle module** `opa-abac-keycloak-directory` — add to `settings.gradle.kts`; `build.gradle.kts`
  with `api(project(":opa-abac-spring-security"))` + `org.keycloak:keycloak-admin-client` (pin the
  version in `gradle/libs.versions.toml`, ADR 0020 §8).
- `KeycloakUserDirectory implements UserDirectory` (`…keycloak.directory`) — builds a `Keycloak`
  admin-client from `client_credentials` (server-url / realm / client-id / client-secret), calls
  `realm.users().search(query, 0, clampedLimit)` (or the username/first/last search), maps to
  `DirectoryUser` with `displayName = username` (falling back to `subject` when blank). Enforces the
  **`limit` clamp** (default 20, hard max 50) and the **fail-closed edges**: any exception / timeout /
  auth failure → **`List.of()`** (log WARN, never throw); **blank `q` → `List.of()` without calling
  Keycloak** (ADR 0020 §3).
- `KeycloakDirectoryProperties` — `@ConfigurationProperties("opa.abac.directory.keycloak")`:
  `enabled`, `server-url`, `realm`, `client-id`, `client-secret`.
- Consumers named: the starter's `OpaDirectoryAutoConfiguration` (T3) constructs this bean; no other
  caller in this ticket.

**Acceptance.** [[10-QA-TEST-CASES]] **U2** (limit clamp: <=0→20, >50→50; blank `q`→empty with **no**
Keycloak call) + **I2** (in-process `com.sun.net.httpserver.HttpServer` stub standing in for the Keycloak
admin API: a matching search → mapped `DirectoryUser`s bounded by limit; a 5xx / connection-refused / a
token-grant 401 → **empty list**, never an exception; a user with a blank username → `displayName ==
subject`). `./gradlew :opa-abac-keycloak-directory:test`.

**What NOT to touch.** The port's contract (T1) — this **implements** it, it does not change it. Never let
an exception escape `search` (fail-closed, §3). Never widen the disclosure past `{subject, displayName}`
(map only those two). No Spring `@Component`/auto-wiring in this module — the starter (T3) owns wiring;
this module ships plain classes. Build-breaker: adding the module to `settings.gradle.kts` must land in
this commit or the build won't see it.

---

## T3 — Starter auto-config (`@ConditionalOnClass` + `@ConditionalOnProperty` + NoOp fallback)

**Goal.** Wire the Keycloak impl into the published starter **only when opted in**, preserving the
lean-starter promise — exactly the B3 Resilience4j pattern.

**Deliverables.**
- `OpaDirectoryAutoConfiguration` (`opa-abac-spring-boot-starter`, `…autoconfigure`):
  `@ConditionalOnClass(name = "<keycloak admin client class>")` **+** `@ConditionalOnProperty(prefix =
  "opa.abac.directory.keycloak", name = "enabled", havingValue = "true")` → a
  `KeycloakUserDirectory` `@Bean` built from `KeycloakDirectoryProperties`; **plus** a
  `NoOpUserDirectory` `@Bean @ConditionalOnMissingBean(UserDirectory.class)` so a `UserDirectory` always
  exists (fail-closed default). Register in `AutoConfiguration.imports`.
- Add `opa-abac-keycloak-directory` as an **optional** (`compileOnly`/documented-optional) dependency of
  the starter so `@ConditionalOnClass` can see the type without forcing it on adopters (mirror how the
  starter treats R4j).
- Consumers named: any app on the starter classpath; the endpoint (T4) injects the resulting
  `UserDirectory` bean.

**Acceptance.** [[10-QA-TEST-CASES]] **I3** (`ApplicationContextRunner`: with the Keycloak client absent
**or** `enabled` unset → the context has a `NoOpUserDirectory` and **no** `KeycloakUserDirectory`; with
the client present **and** `enabled=true` + properties → the context has a `KeycloakUserDirectory` and no
`NoOp`; an adopter-supplied `UserDirectory` bean wins via `@ConditionalOnMissingBean`).
`./gradlew :opa-abac-spring-boot-starter:test`.

**What NOT to touch.** The lean-starter promise — a bare adopter (no Keycloak, no `enabled`) must get the
**`NoOp`**, never a Keycloak bean and never a startup failure (**off-state is a first-class tested path**,
§wiring). Don't make the Keycloak module a hard `api` dependency of the starter (that would drag Keycloak
onto every adopter). `opa-abac-core` untouched.

---

## T4 — `search` endpoint under `/api/v1/users` (spec + controller + bounded-list DTO)

**Goal.** Expose the port over HTTP — a bearer-only `search` sub-path of `/api/v1/users` returning a
bounded plain list.

**Deliverables.**
- `user-mgmt-api.yaml` — a `searchUsers` operation on the `search` sub-path of `/api/v1/users`, query
  params `q` (string) + `limit` (int, optional), response a **`DirectoryUserList`** schema
  `{items:[{subject,displayName}], limit}` (a bounded plain list — **not** the `Page` envelope, ADR 0020
  §5). Regenerate.
- `UserController.searchUsers` (`…usermgmt.web`) — inject `UserDirectory` (the starter bean), call
  `search(q, limit)`, map to `DirectoryUserList`. Bearer-only (no `@OpaPreAuthorize` — consistent with
  the ungated `GET /users`, §7). The endpoint holds **no** Keycloak knowledge (URL-agnostic, §6).
- Consumers named: the SPA member picker (T6) calls this; against `NoOp` it returns an empty list until
  T5's realm config + T3's `enabled=true` light up the Keycloak impl.

**Acceptance.** [[10-QA-TEST-CASES]] **I4** (MockMvc / slice: with a stub `UserDirectory` bean returning
2 rows, `GET` the search path `?q=al&limit=10` → 200, `{items:[…2…], limit:10}`; with `NoOpUserDirectory`
→ 200, `{items:[], limit:…}` — **200 empty, never 404/500**; the response carries only
`subject`+`displayName`). `./gradlew :example-user-management-service:test`.

**What NOT to touch.** The endpoint returns a **bounded plain list**, never a `Page` (no fake `count`).
Disclosure is `{subject, displayName}` only (the DTO is the ceiling, §7). Bearer-only — do **not** invent
a `directory:*` gate. An empty/absent/unmatched result is **200 empty**, never 404 and never an error
that distinguishes outage from empty (the no-oracle rule, §3). No `POST /users` / membership change.

---

## T5 — Realm config: `catalog-directory` client + `view-users` + deploy wiring

**Goal.** Stand up the least-privilege service account and wire the module's config so the live rig can
actually search Keycloak.

**Deliverables.**
- `infra/keycloak/realm-export.json` — a **`catalog-directory`** confidential client
  (`serviceAccountsEnabled: true`, a demo secret), with the **`realm-management` → `view-users`** role
  mapped to its service account (and nothing else — least privilege, ADR 0020 §4).
- `deploy.sh` / `infra/compose.usermgmt.yaml` — pass the module's config to the user-service pod when the
  directory is enabled: `opa.abac.directory.keycloak.enabled=true`, `server-url=http://keycloak:8888`
  (**in-network**, §6), `realm=catalog-demo`, `client-id=catalog-directory`, the secret. Gate behind an
  opt-in flag (e.g. `ENABLE_DIRECTORY=1`) so the default rig is unchanged.
- `scripts/postman/seed-demo-data.sh` — ensure the realm has a couple of **never-provisioned** accounts
  (e.g. `carol`, plus the existing unbound `outsider`) so the e2e can prove the directory returns
  accounts with **no** `users`-table row.
- Consumers named: the `KeycloakUserDirectory` bean (T2/T3) reads this config; the e2e (T6) exercises it.

**Acceptance.** [[10-QA-TEST-CASES]] **E-pre** (rig boots with `ENABLE_DIRECTORY=1`; the user-service log
shows the `KeycloakUserDirectory` bean active, not `NoOp`; a manual `client_credentials` token for
`catalog-directory` can call the admin `view-users` endpoint in-network and is **denied** any write/admin
scope). Verified during T6's e2e bring-up.

**What NOT to touch.** The service account gets **only** `view-users` — no other `realm-management` role
(least privilege; a wider grant is the finding this design avoids, §4). Keep `catalog-directory` a
**separate** client from `catalog-gateway` (distinct trust roles). The default rig (no `ENABLE_DIRECTORY`)
stays byte-for-byte unchanged. The secret is a **demo** secret, scoped to the local rig (clean-room: it is
obviously demo-only, like `catalog-gateway-secret`).

---

## T6 — e2e (newman) + SPA picker rewrite + docs + folder move

**Goal.** Prove the directory end-to-end through the gateway, flip the SPA member picker to search the
directory, reconcile the docs, and move the folder to `implemented/`.

**Deliverables.**
- **e2e (newman):** extend the user-service matrix (no new collection): with `ENABLE_DIRECTORY=1`, a
  bearer `GET` of the search path `?q=<prefix>` returns realm accounts **including a never-provisioned
  one** (carol/outsider — proving it searches the directory, not the `users` table); `?q=` blank → empty;
  a `limit` above 50 is clamped (≤50 items). Assert the **actual cut** (which accounts, counts), not just
  shape. (An outage case is covered by unit/IT, not e2e — hard to fault-inject Keycloak in the rig.)
- **SPA picker rewrite** (`example-demo-ui/src/teams.tsx`, `api.ts`): the "Search the user directory…"
  input calls the new search endpoint (debounced `q`) instead of filtering the provisioned
  `listAllUsers()` set; on select of a never-provisioned account, provision via the existing `ensureUser`
  (`POST /users`, now one-shot thanks to Slice 1's `?subject`) then `addMember`. Update the empty-state
  copy (no longer "only provisioned profiles").
- **Docs:** author/extend `docs/guides/` (a directory/identity-search guide, or a section in the
  team-authorization guide) covering the port, the optional module, the fail-closed/no-oracle contract,
  and the realm service-account setup. Tick the [[USER-DIRECTORY-PORT]] status table. Confirm
  [[0020-user-directory-port|ADR 0020]] is linked from the ADR index + roadmap.
- **Folder move:** `git mv docs/to-do/planning/USER-DIRECTORY-PORT docs/to-do/implemented/`, flip the
  index frontmatter `status/planned → status/done`, add a **Shipped** banner.

**Acceptance.** [[10-QA-TEST-CASES]] **E1–E3** (the newman assertions above, green through the rig) +
`./gradlew build` green (all modules incl. the new one + both example apps + codegen + Testcontainers
ITs). SPA: the picker searches the directory (verified in the browser preview — typing a prefix issues a
single search call; selecting a never-provisioned account provisions then adds them).

**What NOT to touch.** The picker still provisions via the **existing** `POST /users` (provisioning stays
out of the port, §1) — do not move provisioning into the directory. Render an outage and a genuine-empty
**identically** in the UI (the no-oracle rule, §3) — no "directory unavailable" banner. Honor the rig
caveats: mint tokens **in-network**; no rego changed, so no OPA restart. Do NOT push / open a PR / touch
`main`.

---

## Cross-cutting acceptance

- `./gradlew build` green — all modules **including the new `opa-abac-keycloak-directory`** + both example
  apps + OpenAPI codegen + Testcontainers ITs (JDK 21).
- The user-service newman matrix green through the rig with `ENABLE_DIRECTORY=1`
  (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2`).
- **Fail-closed / no-oracle holds:** `search` returns `[]` on every error edge and never throws; an
  outage and a genuine-empty are indistinguishable to the caller and the UI (verified by U2/I2 + the SPA).
- **Lean-starter preserved:** a bare adopter (no Keycloak, no `enabled`) gets `NoOpUserDirectory` and a
  clean context (I3).
- **`opa-abac-core` untouched; no rego; no schema change** (the directory has no persistence) —
  `ddl-auto: validate` boots clean.
