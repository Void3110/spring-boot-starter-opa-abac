---
tags:
  - status/planned
  - type/architecture
  - area/security
  - area/api
---

# USER-DIRECTORY-PORT — design

> The settled design for [[USER-DIRECTORY-PORT|Slice 2]] — the Keycloak-admin user-directory port.
> Grilled to 8 pinned forks (2026-07-06); the rationale lives in **[[0020-user-directory-port|ADR 0020]]**
> (this design references it, it is not repeated here). Slice 1 ([[DIRECTORY-QUERY-FILTERS]]) — the
> `listAll*`-killer filters — ships first and is a soft prerequisite (its `?subject` filter makes the
> SPA's provision-on-select one-shot).

## 1. The mechanism

The SPA member picker can only offer **provisioned** users (`GET /users`). To add a teammate who has
never logged in, search the **identity directory** (all realm accounts). The library gets a pure
**search** seam; a concrete Keycloak impl lives in its own optional module; a bearer-only endpoint
exposes it. Every integration point is a **named** new seam:

```
opa-abac-spring-security  (…security.directory):
    UserDirectory            interface  — search(String query, int limit) -> List<DirectoryUser>
    DirectoryUser            record     — (String subject, String displayName)  [the type-bounded disclosure]
    NoOpUserDirectory        default    — returns [] for every query  (the lean-starter fallback)

opa-abac-keycloak-directory  (NEW MODULE, …keycloak.directory):     [optional dependency]
    KeycloakUserDirectory    impl       — client_credentials -> Keycloak admin API (view-users);
                                          clamps limit (default 20, hard max 50); fail-closed to []
    KeycloakDirectoryProperties         — server-url / realm / client-id / client-secret / enabled
    build.gradle.kts                    — api(project(":opa-abac-spring-security")) + org.keycloak:keycloak-admin-client

opa-abac-spring-boot-starter  (…autoconfigure):
    OpaDirectoryAutoConfiguration       — @ConditionalOnClass(keycloak admin client) + @ConditionalOnProperty
                                          (opa.abac.directory.keycloak.enabled) -> KeycloakUserDirectory bean;
                                          NoOpUserDirectory as @ConditionalOnMissingBean fallback
                                          (mirrors OpaResilienceAutoConfiguration, B3)

example-user-management-service  (…usermgmt.web):
    UserApi / UserController.searchUsers — GET the `search` sub-path of /api/v1/users?q=&limit=  (bearer-only)
    DirectoryUserList DTO (openapi)      — { items:[{subject,displayName}], limit }  (a bounded plain list, NOT a Page)

infra + SPA:
    infra/keycloak/realm-export.json     — a `catalog-directory` confidential client (serviceAccounts) +
                                           the realm-management `view-users` role mapping
    deploy.sh / infra/compose.usermgmt   — wire the module's config (server-url http://keycloak:8888, realm, secret)
    example-demo-ui/src/teams.tsx        — the member-picker consumes the search endpoint (replaces the
                                           provisioned-only candidate list)
```

## 2. Pinned forks (see [[0020-user-directory-port|ADR 0020]] for the full rationale)

1. **Pure search read-model** — provisioning is OUT of scope; the SPA provisions on select via the
   existing `POST /users`. The port never mutates.
2. **Port in `opa-abac-spring-security`** (`…security.directory`), mirroring `ResourceOwnershipResolver`
   (ADR 0019); core stays framework-free.
3. **Keycloak impl in its own optional module** `opa-abac-keycloak-directory`, auto-wired like B3's R4j:
   `@ConditionalOnClass` + `@ConditionalOnProperty`, `NoOpUserDirectory` as the `@ConditionalOnMissingBean`
   fallback — the lean-starter promise holds.
4. **Least-privilege Keycloak auth** — a dedicated `catalog-directory` confidential client,
   `client_credentials`, granted only `realm-management` → `view-users`. No human credential; a separate
   trust role from `catalog-gateway`.
5. **Bearer-only `search` endpoint under `/api/v1/users`**, returning a **bounded plain list**
   `{items:[{subject,displayName}], limit}` — not a `Page` envelope (no fake `count`).
6. **URL encapsulation** — the Keycloak server URL (`http://keycloak:8888` in-network) is private to the
   module + its config; the port and endpoint stay URL-agnostic. `KC_HOSTNAME_ADMIN_URL=localhost:28888`
   is a console-URL rewrite, NOT the REST path (the module calls in-network `:8888`).
7. **Authorization** — bearer-only (consistent with the ungated `GET /users`); disclosure **type-bounded
   to `{subject, displayName}`** in the DTO (no impl can widen it); the real gate for *acting* on a
   result stays `team:add-member`. No new `directory:*` permission-model surface.
8. **Library-layer choice** — the official `org.keycloak:keycloak-admin-client` (released independently,
   compatible with the last KC server 26.x, supports `client_credentials`, Java 11+).

## 3. Fail-closed / no-oracle posture

**`search` fails CLOSED to an empty list, never throws** (the `GovernedScopeResolver` empty-on-error
shape — NOT the B2 tri-state). The port is **binary** (empty-or-results). Every edge lands on `[]`:

| Edge | Behavior |
|---|---|
| Keycloak unreachable / timeout / 5xx | `[]` + WARN (distinct message) |
| Admin token grant fails (bad secret / misconfig) | `[]` + WARN (distinct message) |
| Blank / whitespace-only `q` | `[]` **without calling Keycloak** (never enumerate the realm) |
| `q` present, zero matches | `[]` (the honest empty) |
| `limit` ≤ 0 or absent | default **20**; `limit` > 50 → clamped to **50** (hard max — never unbounded) |
| Keycloak user with null/blank username | `displayName = subject` (always renderable; subject is the join key) |
| Module absent / `enabled=false` | `NoOpUserDirectory` → `[]` for every query (zero-config safe) |

**No-oracle security property (load-bearing):** an outage and a genuine-empty are **indistinguishable to
the caller and the UI** — both render "no matches." Surfacing "the directory is down" or "N users exist"
would leak backend state / realm size, so the identical `[]` is **deliberate**. Outage vs empty differs
**only in the WARN log** (for operators). The SPA must render both the same.

## 4. Considered & rejected

See [[0020-user-directory-port|ADR 0020]] "Considered & rejected" — the merged-view join (owns
provisioning), master-realm admin (privilege excess), gateway-client reuse (trust-role conflation), a
`directory:*` gate (needless permission surface), a tri-state port (info-leak + complexity), and a
core-module port (core stays framework-free).

## 5. Scope boundary (NOT in this slice)

- **Provisioning** (create/join) — stays the SPA's `ensureUser`/`POST /users`; the directory never mutates.
- **The `?subject` / `?targetType` filters** — that is Slice 1 ([[DIRECTORY-QUERY-FILTERS]], ships first).
- **Any OPA / rego change** — search is bearer-only, no policy decision; the acting-gate (`team:add-member`)
  already exists.
- **Paging the directory** — a bounded top-`limit` list is the whole contract; no cursor, no `count`.

## Related

- [[USER-DIRECTORY-PORT]] · [[01-DECOMPOSITION]] · [[10-QA-TEST-CASES]]
- [[0020-user-directory-port|ADR 0020]] (the decisions) · [[0019-pluggable-cross-service-ownership|ADR 0019]] (the mirrored SPI)
- [[DIRECTORY-QUERY-FILTERS]] (Slice 1) · [[POC-ROADMAP]] (Phase 7)
