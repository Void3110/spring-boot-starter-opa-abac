---
tags:
  - status/active
  - type/guide
  - area/security
  - area/spring
  - area/user-service
  - area/api
---

# The user-directory port — identity search beyond the provisioned set

> Slice 2 of the user-directory work (ADR [[0020-user-directory-port|0020]]). A pure **search**
> read-model SPI in the library — `UserDirectory.search(query, limit) → List<DirectoryUser>` — with a
> concrete Keycloak-admin implementation in its **own optional module**, a bearer-only `search`
> endpoint on the user-service, and the SPA member picker riding it. The whole seam **fails closed to
> an empty list** and is deliberately **no-oracle**: an outage and a genuine zero-match are
> indistinguishable to callers. This guide is the shipped contract; the design record (the 8 pinned
> forks) lives in the USER-DIRECTORY-PORT package under `docs/to-do/implemented/`.

## Why this seam exists

Team membership is the sole access path to a resource (ADR
[[0018-team-scoped-resource-isolation|0018]]) — so *adding people to teams* is the demo's most
security-sensitive UI motion. Before this slice the member picker could only offer **provisioned**
profiles (rows in the user-service `users` table): adding a teammate who had never logged in was
impossible. The identity directory (every realm account the IdP knows) is the correct candidate pool —
but the library had no seam for it, and a directory search is a user-enumeration / PII surface that
needs its authorization and disclosure pinned up front.

## The seam, layer by layer

| Layer | Type | What it owns |
|---|---|---|
| `opa-abac-spring-security` (`…security.directory`) | `UserDirectory` · `DirectoryUser` · `NoOpUserDirectory` | The port: the search contract, the **type-bounded disclosure** (`subject` + `displayName`, nothing wider), the always-empty default. |
| `opa-abac-keycloak-directory` (**optional module**) | `KeycloakUserDirectory` · `KeycloakDirectoryProperties` | The Keycloak-admin impl: `client_credentials` grant, the users `search` REST call, the limit clamp, every fail-closed edge. No Spring beans of its own; Keycloak types never leak out (`implementation` scope). |
| `opa-abac-spring-boot-starter` | `OpaDirectoryAutoConfiguration` | Wiring: `@ConditionalOnClass` (admin client) **+** `@ConditionalOnProperty` (`opa.abac.directory.keycloak.enabled=true`) → the Keycloak bean; otherwise the `NoOp` via `@ConditionalOnMissingBean`. An adopter-supplied `UserDirectory` always wins. The B3-R4j optional-module pattern exactly. |
| `example-user-management-service` | `UserApi.searchUsers` / `UserController` | The HTTP surface: a bearer-only `search` sub-path of `/api/v1/users` returning a **bounded plain list** `{items, limit}` — not a page envelope. URL/Keycloak-agnostic (injects the port). |
| `example-demo-ui` | `searchDirectory` + the member picker | Debounced server-side search; **provision-on-select** — picking a never-provisioned account runs the existing `ensureUser` (`POST /users`) then `addMember`. The directory itself never mutates. |

## The fail-closed / no-oracle contract (load-bearing)

`search` returns an **empty list on every non-affirmative outcome and never throws**:

| Edge | Result |
|---|---|
| Keycloak unreachable / timeout / 5xx | `[]` + WARN |
| Token grant fails (bad secret, misconfig) | `[]` + a **distinct** WARN |
| Blank / whitespace `q` | `[]` **without calling Keycloak** (the realm is never enumerable) |
| Zero matches | `[]` (the honest empty) |
| `limit ≤ 0` / `> 50` | clamped to **20** / **50** (`UserDirectory.clamp` — one rule for the impl and the endpoint's echoed `limit`) |
| Null/blank username on a row | `displayName = subject` (always renderable) |
| Module absent / `enabled` unset | `NoOpUserDirectory` → `[]` for every query |

**No-oracle:** an outage and a genuine empty are *deliberately indistinguishable* to the caller and the
UI — both are a `200` with empty `items`. Surfacing "the directory is down" (or realm size) would leak
backend state, so the identical empty is a security property, not a bug. Outage vs empty differ only in
the operator-facing WARN log; the SPA renders both as "No directory accounts match."

## Authorization & disclosure

- **Bearer-only** endpoint (consistent with the ungated `GET /users`): finding a subject grants
  nothing. The authorization boundary for **acting** on a result is the existing
  `team:add-member` gate. No `directory:*` permission surface exists.
- **Disclosure is the type:** `DirectoryUser(subject, displayName)` is the ceiling — email, roles, and
  attributes cannot leak because no wider type exists on the path (asserted by reflection in the
  library test and at the JSON wire in the MockMvc test).
- **Least-privilege service account:** the `catalog-directory` confidential client holds only
  `realm-management → view-users`. Live-proved on the rig: users read `200`, user create/update/delete
  all `403`. A leaked demo secret's blast radius is "list demo usernames," never token validation
  (separate client from `catalog-gateway`) and never a write.

## Adopting it (any Spring Boot app on the starter)

```yaml
# build.gradle.kts: runtimeOnly("dev.dmitriikonovalov:opa-abac-keycloak-directory")
opa:
  abac:
    directory:
      keycloak:
        enabled: true
        server-url: http://keycloak:8888    # as seen by the SERVICE (in-network in the rig)
        realm: catalog-demo
        client-id: catalog-directory
        client-secret: ${DIRECTORY_CLIENT_SECRET}
```

Without the dependency + flag, injection points get the `NoOp` (empty) directory — a bare adopter pays
nothing and stays fail-closed. A custom impl (LDAP, SCIM, a static list) is one `UserDirectory` bean —
the starter backs off.

## The local rig

```bash
ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2   # force-enables OIDC + user-service
```

The flag wires the config above into the user-service pod (see `infra/compose.usermgmt.yaml`) against
the realm's `catalog-directory` client. **The server URL is the in-network `http://keycloak:8888`** —
`KC_HOSTNAME_ADMIN_URL` (`localhost:28888`) is a console-URL rewrite, *not* the REST path (ADR 0020
§6). The e2e cells live in the team matrix (`scripts/postman/run-team-matrix.sh`): a never-provisioned
account is found while its provisioned `?subject` lookup stays an empty page, blank `q` stays empty,
and the 50 clamp holds end-to-end.

## Related

- [[0020-user-directory-port|ADR 0020]] (the 8 pinned forks) ·
  [[0019-pluggable-cross-service-ownership|ADR 0019]] (the mirrored SPI shape)
- [[TEAM-BASED-AUTHORIZATION]] (why the picker matters) · [[E2E-TESTING]] (the in-network token caveat)
- [[HTTP-RESILIENCE]] (the optional-module wiring precedent this mirrors)
